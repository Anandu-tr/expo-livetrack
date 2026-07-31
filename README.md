# expo-livetrack

Reliable background-location tracking for Expo apps — built for field-staff route
and distance reporting where **missing points are not acceptable**.

> **Status:** native (Android + iOS) is implemented but **pending on-device QA**.
> The JS API, config plugin, and wire contract are stable and tested. Treat the
> native layer as beta until it has been verified on real hardware.

## What it is / the problem it solves

Most "background location" setups silently drop points: the OS kills the process,
location services get toggled off, permissions are revoked, the battery optimiser
freezes the app, or the user spoofs their GPS. For route/distance tracking of field
staff, every one of those is a data-integrity hole.

`expo-livetrack` is a thin JS surface over a native foreground-service tracker that:

- **Captures continuously in the background** via an Android foreground service
  (`type=location`) and iOS background location, re-arming after reboot/app update.
- **Buffers locally and uploads in batches**, deleting records only after the
  server acknowledges them — so nothing is lost across network blips or crashes.
- **Is tamper-evident**: every fix carries a mock-location flag, and the tracker
  emits diagnostic events when location is turned off, permission is revoked, the
  battery optimiser is on, or a mock provider is detected.
- **Keeps all UX in your app**: the package never shows its own popups. It emits
  events (e.g. `LOCATION_OFF`) and exposes actions (e.g. `requestEnableLocation()`)
  so your app owns the wording and styling required for store compliance.

## Install

```sh
npx expo install expo-livetrack
```

This package contains custom native code, so it **does not run in Expo Go**. You
need a [custom dev build](https://docs.expo.dev/develop/development-builds/introduction/):

```sh
npx expo prebuild
npx expo run:android   # or: npx expo run:ios
```

Requires Expo SDK 54+, React Native 0.81+.

## Plugin config (required)

Add the config plugin to `app.json` (or `app.config.js`). Both iOS purpose strings
are **required** — the plugin **throws at prebuild** if either is missing or empty.

```json
{
  "expo": {
    "plugins": [
      [
        "expo-livetrack",
        {
          "locationWhenInUse": "We use your location to record your field visits and route while you are using the app.",
          "locationAlways": "We continue to record your location in the background, even when the app is closed, so your full route and distance travelled are captured for work reporting."
        }
      ]
    ]
  }
}
```

What the plugin injects:

- **iOS:** `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription`,
  `NSLocationAlwaysUsageDescription` (from your props), and the `location`
  `UIBackgroundMode`.
- **Android:** fine/coarse/background location, foreground-service (+`_LOCATION`),
  boot, activity-recognition and notification permissions; the tracking
  foreground service (`type=location`); and boot / provider-changed receivers.

> ⚠️ **Purpose strings matter for review.** Vague strings (e.g. "for location")
> get **App Store rejections**. Be specific about *what* is collected, that it
> happens *in the background*, and *why*. **Missing** strings throw at prebuild,
> by design, so you can't accidentally ship a store-rejectable binary.

## API reference

```ts
import { LiveTracker } from 'expo-livetrack';
```

### Methods

| Method | Returns | Description |
| --- | --- | --- |
| `start(config)` | `Promise<void>` | Start background tracking. `config`: `{ url, token, userId, cadence? }`. |
| `stop()` | `Promise<void>` | Stop tracking and the foreground service. |
| `getState()` | `Promise<TrackerState>` | Current tracker state (see below). |
| `requestPermissions()` | `Promise<TrackerState>` | Request location permissions; resolves with the updated state. |
| `ensureNotKilled()` | `Promise<void>` | Re-arm the service if the OS killed it; nudge the user about battery optimisation if needed. |
| `requestEnableLocation()` | `Promise<boolean>` | Ask the OS to enable location services. Resolves `true` if they end up enabled. Call this from **your own** `LOCATION_OFF` handler. |
| `onLocation(cb)` | `EventSubscription` | Subscribe to per-record location fixes. |
| `onEvent(cb)` | `EventSubscription` | Subscribe to lifecycle/diagnostic events. |
| `onSyncError(cb)` | `EventSubscription` | Subscribe to upload failures. |

All three `on*` methods return an `EventSubscription`; call `.remove()` to
unsubscribe (e.g. in a React `useEffect` cleanup).

### `StartConfig`

```ts
interface StartConfig {
  url: string;     // POST endpoint for batched uploads
  token: string;   // sent as `Authorization: Bearer <token>`
  userId: string;  // identifies the tracked user; stamped on every record (`u`)
  cadence?: Cadence;
}

interface Cadence {
  movingIntervalMs?: number; // default 12000
  movingDistanceM?: number;  // default 30
  stillIntervalMs?: number;  // default 120000
  batchSize?: number;        // default 50 (clamped natively to 1..500)
  maxAccuracyM?: number;     // default 50
  maxUploadAttempts?: number; // default 15 — see "Wire contract" below
}
```

### `TrackerState`

```ts
interface TrackerState {
  tracking: boolean;
  permission: 'granted' | 'denied' | 'background';
  locationServices: boolean;
  batteryOptIgnored: boolean;
  bufferedCount: number;
}
```

### Events (`onEvent`)

Each event is `{ e: EventType, t: number, l?, g?, b? }`. `EventType` is one of:

| Event | Meaning |
| --- | --- |
| `LOCATION_OFF` | Device location services were turned off. |
| `LOCATION_ON` | Device location services were turned back on. |
| `PERMISSION_REVOKED` | Location permission was revoked. |
| `PERMISSION_GRANTED` | Location permission was granted. |
| `MOCK_DETECTED` | A mock/spoofed location provider was detected. |
| `BATTERY_OPT_ON` | Battery optimisation is active (may freeze tracking). |
| `HEARTBEAT` | Periodic liveness signal from the service. |

### `LocationRecord` (`onLocation`) and `SyncError` (`onSyncError`)

```ts
interface LocationRecord {
  l: number;   // latitude
  g: number;   // longitude
  t: number;   // epoch ms
  s: number;   // speed (m/s)
  acc: number; // accuracy (m)
  b: number;   // battery %
  c: boolean;  // charging
  act: string; // activity type
  mock: boolean; // mock-location flag
}

interface SyncError {
  message: string;
  status?: number;
  bufferedCount?: number;
  droppedCount?: number; // rows evicted after exhausting maxUploadAttempts
}
```

## Wire contract (`POST <url>`)

The native uploader batches buffered points + events and POSTs them to your
configured `url` with `Authorization: Bearer <token>`. Each record carries a
client-generated `id` (for acknowledgement) and the `u` (userId) on top of the
fields above.

**Request body:**

```jsonc
{
  "points": [
    {
      "id": "uuid-1",      // client-generated; used for ACK
      "u": "demo-user",    // userId
      "l": 12.97, "g": 77.59, "t": 1717200000000,
      "s": 1.4, "acc": 8, "b": 73, "c": false,
      "act": "walking", "mock": false
    }
  ],
  "events": [
    { "id": "uuid-2", "u": "demo-user", "e": "LOCATION_OFF", "t": 1717200001000 }
  ]
}
```

**Response (any HTTP 2xx):**

```json
{ "acceptedIds": ["uuid-1", "uuid-2"] }
```

**Delete-on-ACK:** the client deletes only the buffered records whose `id` is in
`acceptedIds`, and **retries the rest** on the next batch. This is what guarantees
no point is lost across network failures or process death. The server is expected
to dedupe (e.g. by `(u, t)`) so retried records don't create duplicates.

Ids you return are **intersected with the ids the client sent**. Returning your own
primary keys instead of echoing the client's `id` acknowledges nothing, and the
batch will be retried.

**Tolerated response variants.** The shape above is the contract, but the parser
also accepts these so a non-conforming backend degrades instead of stalling:

| Variant | Example |
| --- | --- |
| Numeric ids | `{"acceptedIds": [1043, 1044]}` |
| Envelope | `{"success": true, "data": {"acceptedIds": [...]}}` (also `result`) |
| Field aliases | `acceptedIDs`, `accepted`, `ids` |
| Objects | `{"acceptedIds": [{"id": "1043"}]}` (also `rowId`, `clientId`) |
| Bare array | `["1043", "1044"]` |

**If the client cannot read your acknowledgement** — an empty body, an unknown
shape, or ids that were never sent — it does **not** treat the 2xx as success. It
reports a `upload-ack-empty` / `upload-ack-foreign` diagnostic (including a
truncated response snippet, so the actual shape is visible in your crash reporter),
backs off, and increments a per-row attempt counter. After `maxUploadAttempts`
(default 15) the affected rows are dropped and a `buffer-evicted` diagnostic plus an
`onSyncError` with `droppedCount` are emitted.

This bound exists because the alternative is worse: without it a single unreadable
ack makes the device re-upload the same batch indefinitely.

## App-controlled enable-location pattern

The package **never shows its own popup**. When location services are off it emits
a `LOCATION_OFF` event; your app decides whether/how to prompt and owns the copy.
The CTA calls `requestEnableLocation()`:

```tsx
useEffect(() => {
  const sub = LiveTracker.onEvent((evt) => {
    if (evt.e === 'LOCATION_OFF') {
      // This popup is defined by the APP, not the package. The package only
      // emits LOCATION_OFF and exposes requestEnableLocation().
      Alert.alert(
        'Turn on location',
        'Location is off, so we can’t track your route. Turn it back on to keep ' +
          'your visits and distance recorded.',
        [
          { text: 'Not now', style: 'cancel' },
          { text: 'Turn on location', onPress: () => LiveTracker.requestEnableLocation() },
        ]
      );
    }
  });
  return () => sub.remove();
}, []);
```

A full working screen (controls, live status, event log, the disclosure modal and
the enable-location popup) lives in [`example/App.tsx`](./example/App.tsx).

### Trying it locally

1. Start the in-package dummy ingest server: `npm --prefix test-server start`
   (listens on `:8787`, accepts any non-empty Bearer token, exposes
   `GET /debug/received` and `POST /debug/reset`).
2. In `example/App.tsx`, set `TRACK_URL` to `http://<YOUR-LAN-IP>:8787/locations/batch`
   — a device cannot reach `localhost`.
3. Build and run the example as a dev build (not Expo Go).

## Compliance docs

Store-submission material lives in [`docs/compliance/`](./docs/compliance):

- [`prominent-disclosure.md`](./docs/compliance/prominent-disclosure.md) — Google Play
  background-location disclosure + in-app disclosure copy.
- [`play-fgs-justification.md`](./docs/compliance/play-fgs-justification.md) — Foreground
  Service (`location`) justification for the Play Console form.
- [`demo-video-script.md`](./docs/compliance/demo-video-script.md) — script for the Play
  background-location declaration demo video.
- [`data-collection.md`](./docs/compliance/data-collection.md) — exact field list for the
  Play Data Safety form and Apple privacy nutrition labels.

## License

MIT
