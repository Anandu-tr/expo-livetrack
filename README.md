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
| `ensureNotKilled()` | `Promise<void>` | Re-arm the service if the OS killed it; nudge the user about battery optimisation if needed. Throttled (max once per 10 min) and capped (max 3 consecutive attempts) to prevent prompt loops on devices where the OS doesn't reliably reflect the grant. |
| `requestEnableLocation()` | `Promise<boolean>` | Ask the OS to enable location services. Resolves `true` if they end up enabled. Call this from **your own** `LOCATION_OFF` handler. |
| `replay(fromMs, toMs)` | `Promise<ReplayResult>` | Re-send archived location rows captured in `[fromMs, toMs)` with their **original** ids and times. Needs `archiveDays > 0`. Rejects with `ERR_REPLAY_AUTH` / `ERR_REPLAY_BUSY`. |
| `getCoverage(fromMs, toMs, gapMs?)` | `Promise<DayCoverage>` | What this device captured in the window (archive ∪ still-unsynced buffer): count, first/last time, gaps longer than `gapMs` (default 10 min) with distance covered meanwhile. |
| `clearArchive()` | `Promise<void>` | Delete the on-device archive. Call on sign-out. |
| `getArchiveStats()` | `Promise<ArchiveStats>` | Archived row count, oldest timestamp, approximate on-disk size. |
| `getOemAutostartInfo()` | `Promise<OemAutostartInfo>` | Android: `{ manufacturer, brand, hasKnownSettings, hasBatterySaverSettings }`. Whether this phone's maker has its own background-kill settings (Xiaomi/POCO/Redmi, Oppo, Vivo, Realme). |
| `openAutostartSettings()` | `Promise<OemAutostartResult>` | Android: best-effort launch of the OEM autostart screen; falls back to app info. |
| `openBatterySaverSettings()` | `Promise<OemAutostartResult>` | Android, Xiaomi/POCO/Redmi: open the MIUI per-app battery saver ("No restrictions" stops MIUI killing tracking); falls back to app info. |
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
  archiveDays?: number; // default 0 (off). Keep acknowledged location rows on-device for N days (max 365, 400k rows)
}

interface ReplayResult { sent: number; accepted: number; batches: number; failedBatches: number }
interface CoverageGap { fromT: number; toT: number; durationMs: number; distanceM: number }
interface DayCoverage { count: number; firstT: number | null; lastT: number | null; gaps: CoverageGap[] }
interface ArchiveStats { count: number; oldestT: number | null; sizeBytes: number }

interface Cadence {
  movingIntervalMs?: number; // default 12000
  movingDistanceM?: number;  // default 30
  stillIntervalMs?: number;  // default 120000
  batchSize?: number;        // default 50 (clamped natively to 1..500)
  maxAccuracyM?: number;     // default 50
  maxUploadAttempts?: number; // default 15 — see "Wire contract" below
}
```

How the cadence is applied:

- **Moving (Android):** fixes are requested every `movingIntervalMs`, and every 3 s while a fix in the last 2 min reported vehicle speed (≥ 3 m/s); walking pace and stops stay on the slower cadence to save battery. A point is written about every `movingIntervalMs`, plus wherever the heading turns by 30° or more over a 15 m+ leg, so corners are drawn instead of cut. The `STATS` heartbeat's `fc` field reports the seconds spent on the 3 s cadence.
- **Moving (iOS):** updates arrive every `movingDistanceM` of movement and every one that passes the gate is written, so corners are already drawn at that resolution.
- **Parked (both):** a fix that reports ~0 speed and moved less than its own plus the previous fix's accuracy is drift, not a move. One point is written every 10 min, stamped with the current time: the current fix as it is when it stays within 3 m of the last point, otherwise the fix since the last point that stayed nearest it (each with its own accuracy). The newest point is never more than ~10 min old, the line barely moves even indoors, and the points keep GPS's natural jitter. That matters because the backend flags 20 consecutive points as spoofing when their distances from the centroid vary by under 0.5 m, all within 2 m. `ParkedReplayTest` replays recorded still nights against that rule. A point never repeats the previous coordinates exactly.
- **Far fixes (both):** a fix reporting no speed that lands far away (indoor scatter does, 300-1000 m) is written only once three fixes agree on the new place, or once fixes head steadily away along one bearing past 1.2 km (a ride on network fixes, which carry no speed). Activity Recognition reporting a vehicle or a bicycle vouches for such fixes for a minute (Android). A fix reporting walking pace that lands farther away than that pace explains is held back the same way (`ic` in `STATS`); one reporting 0 while motion is vouched for is not (some phones' network fixes report 0 instead of no speed). A spur that comes straight back is dropped: within 40 m of where it left, or, for one reporting no motion, within 15% of its length; one held when the phone goes still waits for the next fixes instead of being written at once.
- **Leaving still mode (Android):** besides Activity Recognition, the significant-motion trigger and a fix that moved, a 3 s accelerometer check with the screen off ends still mode when the phone is being carried or ridden (at most one check per 45 s, run on each fix delivered in still mode and while fixes don't prove motion); a recent such check also keeps Activity Recognition's `STILL` and the speed fallback from starting still mode. So do two or more far fixes heading away together past 500 m. Far fixes the gate rejected never count as standing still. Production (Oct 29-30) had up to 30 min of riding in still mode with Activity Recognition silent or reading the ride as `STILL`.

### `TrackerState`

```ts
interface TrackerState {
  tracking: boolean;
  permission: 'granted' | 'denied' | 'background';
  locationServices: boolean;
  batteryOptIgnored: boolean;
  bufferedCount: number;
  killRestarts24h?: number; // Android: silent-kill recoveries in the last 24 h
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
| *(stops)* | With `diagnostics.lifecycleEvents`, `SERVICE_STOPPED` also records **phone switched off** (`r: "shutdown"`, written at power-off and uploaded after the next boot) and, on Android 11+, how the previous process ended, at its real time, read on the next start: **force-stop** (`r: "force_stop"`) or an OS/OEM kill (`r: "killed_low_memory"`, `"killed_other:<description>"`, …). |
| *(internet)* | With `diagnostics.lifecycleEvents`, a `HEARTBEAT` with `r: "NET_OFF"` when the phone has had no working internet for 2 min (its `t` is when the outage began) and `r: "NET_ON"` when it is back. Both upload once online. |
| *(reconciled)* | `LOCATION_ON/OFF`, `PERMISSION_GRANTED/REVOKED` and `BATTERY_OPT_ON` are also checked on every service start and once a minute while tracking, so a change made while the service was dead or the app was closed is still recorded. Such a row carries `r: "reconcile"` and its `t` is when it was noticed, not when it happened. |
| `HEARTBEAT` | Periodic liveness signal from the service. Stored rows carry `r`: `STILL` (still per Activity Recognition), `STILL_NOAR` (still inferred from speed because Activity Recognition was silent for 15 min), or `STATS\|m=..\|ar=..\|d=..\|...` every 10 min when `diagnostics.lifecycleEvents` is on (fix deliveries, gate rejects by reason, probes, minutes since the last Activity Recognition result, seconds on the 3 s moving cadence, and on Android `sm` significant-motion triggers (`-1`: no such sensor), `mc`/`mv` accelerometer checks / of which moving, `ms` their largest spread in hundredths of m/s²). With `diagnostics.lifecycleEvents` on Android also: `MOVING\|<cause>` when still mode ends (`ar`, `sm`, `fix`, `motion`, `travel`), and `REJ\|<reason>\|l=<lat>\|g=<lng>\|a=<acc m>\|s=<speed m/s or ->` for fixes the gate rejected for landing far away (`tp`, `un`, `ic`; up to 20 per 10 min) and, while moving, for accuracy up to 100 m (`acc`; up to 10 per 10 min), at the fix's time. These rows' `l`/`g` stay the last accepted position. |

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

**Archive + replay:** with `archiveDays > 0`, acknowledged location rows are also
copied to a local `archived_points` table (rolling window). `replay(fromMs, toMs)`
re-POSTs those rows to the same `url` with their **original** `id` and `t`. The
server MUST treat a repeated `(u, id)` / `(u, t)` as idempotent (upsert, and still
return the id in `acceptedIds`) and MUST NOT re-add distance to derived daily
summaries on a replay.

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

### Native unit tests

- Android (JUnit): `./gradlew :expo-livetrack:testDebugUnitTest` from the host app's `android/` directory.
- iOS noise gate (`ios/LocationGate.swift` needs only Foundation), no Mac required:
  `docker run --rm -v "$PWD:/src:ro" swift:5.10-jammy bash -c 'cd /tmp && swiftc /src/ios/LocationGate.swift /src/ios-spec/main.swift -o spec && ./spec'`
  (on a Mac: `swiftc ios/LocationGate.swift ios-spec/main.swift -o /tmp/spec && /tmp/spec`). The spec mirrors the Android `LocationGateTest` cases that apply to iOS.

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
