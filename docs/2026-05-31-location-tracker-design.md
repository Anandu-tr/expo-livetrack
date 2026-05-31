# expo-livetrack — Production-Grade Background Location Package

**Status:** Design approved (2026-05-31)
**Type:** Standalone Expo native module (publishable, fully isolated from the host app)
**Origin:** RCA of SIM Connect "location jumping" + "incorrect distance" traced the root cause to *data capture* — the app stops sending (OEM background kills, GPS off, no offline buffer), and `acc` arrives null. This package fixes capture at the source.

---

## 1. Problem & Goals

### Root causes this package addresses (from RCA evidence)
- **Missing points** — background service killed by OEM battery management; gaps of 10–47 min at healthy battery (Gireesh 05-30: 10.8 km straight chord across a 47-min gap). #1 cause of map "jumping."
- **`acc = null`** on most production points → backend's 50 m accuracy gate silently disabled.
- **No offline buffer** — mobile writes straight to Firebase; any network blip = lost point (rural Kerala).
- **Stationary drift floods** — no motion-based sampling.
- **Teleport / mock fixes** counted as travel (24 km orphan jumps; "Possible Fake Location" guessed, never confirmed).
- **Open RTDB** — `".write": true`; any client can write any location.

### Goals
1. **No point ever lost** under app-kill, reboot, or offline — durability via on-device buffer, deleted only on server ACK.
2. **Survive** OEM background kills, Doze, low-memory death, reboot, app update.
3. **Tamper-evidence** for what's technically unstoppable (GPS off, permission revoked, force-stop, mock) — log explicit events with last-known location.
4. **Store-compliant** — adds zero Play/App Store rejection risk; provides the app's compliance hooks.
5. **Total isolation** — own repo, own semver, own CI; drop into the host app with one install + a config plugin (no manual native edits).

### Non-goals (explicitly out of scope — companion fixes, not this package)
- **Live map rendering** (frontend): must render filtered/snapped path + handle gaps. Package gives clean data; it does not change the map.
- **Live-vs-nightly distance reconciliation** (backend): add velocity ceiling to the real-time consumer + one reconciled number.
- Geofencing / polygon zones (done server-side already), HTTP templating, encryption-at-rest, headless JS tasks.

---

## 2. Architecture — decouple capture from upload

```
┌──────────────── NATIVE FOREGROUND SERVICE (always-on) ────────────────┐
│  (1) CAPTURE              (2) PERSIST                  (3) SYNC          │
│  FusedLocation / CL  →  SQLite (Room / native)  →  batched HTTP uploader │
│  acc + speed + act       marked unsynced            retry/backoff,       │
│  motion-adaptive         survives kill/reboot/      delete row on ACK    │
│                          offline                                         │
└──────────────────────────────┬──────────────────────────────────────────┘
                                │ POST /locations/batch  (JWT)
                                ▼
        NestJS backend: auth → validate (acc>50, mock, dedupe) → write raw_locations
                                ▼
        EXISTING consumer (5 s) + nightly cron  ── UNCHANGED ──
```

**Durability guarantee:** a fix/event is written to SQLite first and removed only after the server returns 200. Capture never depends on connectivity or process lifetime.

**Downstream untouched:** backend writes into `raw_locations` in the exact shape the consumer already expects → `location.consumer.ts` and the nightly cron need no change.

---

## 3. Package structure (standalone Expo module)

```
expo-livetrack/                       ← own repo, own semver, own CI
├── android/   Kotlin   TrackingService(FG) · Room buffer · WorkManager uploader · BootReceiver · MotionDetector · ProviderReceiver
├── ios/       Swift    CLLocationManager(always,bg) · SQLite buffer · URLSession uploader · significant-change relaunch
├── src/       TS       LiveTracker.start(config) · stop() · getState() · onLocation / onEvent / onSyncError
├── plugin/    Config plugin → injects ALL native manifest/Info.plist changes at prebuild
├── example/   Throwaway Expo app for REAL-device testing in isolation
└── docs/      This spec + compliance docs (disclosure copy, FGS justification, demo-video script, data-collection list)
```

Host-app footprint shrinks to: on login `start({ url, token, cadence })`; on logout `stop()`. The package knows nothing about SIM Connect.

---

## 4. JS API (the contract the host app uses)

```ts
LiveTracker.start({
  url: string,            // POST /locations/batch
  token: string,          // JWT for auth
  cadence?: {
    movingIntervalMs?: number;   // default 12000
    movingDistanceM?: number;    // default 30
    stillIntervalMs?: number;    // default 120000 (back off when 'still')
    batchSize?: number;          // default 50
    maxAccuracyM?: number;       // default 50 (on-device drop)
  }
}): Promise<void>;

LiveTracker.stop(): Promise<void>;
LiveTracker.getState(): Promise<{ tracking; permission; locationServices; batteryOptIgnored; bufferedCount }>;
LiveTracker.requestPermissions(): Promise<PermissionResult>;   // app calls AFTER showing prominent disclosure
LiveTracker.ensureNotKilled(): Promise<void>;                  // battery-opt + OEM autostart prompts
LiveTracker.requestEnableLocation(): Promise<boolean>;         // triggers the OS enable-location flow; app decides WHEN to call it

LiveTracker.onLocation(cb);   // debug/UI
LiveTracker.onEvent(cb);      // Tier-2 events (below)
LiveTracker.onSyncError(cb);
```

> **Enable-location popup is app-controlled, never shown by the package.** The package only *detects* GPS-off and *emits* `LOCATION_OFF` (and exposes `requestEnableLocation()`). It renders **no UI** of its own. The host app owns the popup entirely — whether to show it, its copy, timing, frequency, and styling — by listening to `onEvent('LOCATION_OFF')` and (optionally) calling `requestEnableLocation()`, which on Android triggers the native `LocationSettingsRequest` resolution dialog (turn on location without leaving the app) and on iOS deep-links to Settings (iOS forbids programmatic toggling). No popup behavior is configured in the plugin.

---

## 5. Payload & event schema (extends current `{u,l,g,t,s,a,b,c,acc}`)

```jsonc
// location (adds nothing breaking)
{ "u","l","g","t","s","acc","b","c","act","mock":false }

// Tier-2 EVENT records — same row shape + last-known location + reason, via field "e"
{ "u","e":"LOCATION_OFF","t","l","g","b","c" }
{ "u","e":"LOCATION_ON","t","l","g","b" }
{ "u","e":"PERMISSION_REVOKED","t","l","g","b" }
{ "u","e":"MOCK_DETECTED","t","l","g","b" }
{ "u","e":"BATTERY_OPT_ON","t" }
{ "u","e":"HEARTBEAT","t","l","g","b" }      // proof-of-life ⇒ distinguishes stationary from secretly-dead
```

---

## 6. Resilience matrix

### Tier 1 — survive (keep tracking, auto-recover)
| Event | Mechanism |
|---|---|
| OEM kill / swipe-from-recents | FG service `START_STICKY` + AlarmManager watchdog + WorkManager periodic check |
| Low-memory death | `START_STICKY`; buffer means no data lost |
| Reboot / app update | `BootReceiver` (`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`) |
| Doze / standby | FG service Doze-exempt; short wakelocks around fixes |
| Network loss | Buffer-until-ACK |
| Battery-opt re-enabled later | Periodic `isIgnoringBatteryOptimizations()` re-check → re-prompt |

### Tier 2 — technically unstoppable → detect + log + report
| User action | Keep tracking? | Package response |
|---|---|---|
| GPS / location services OFF | ❌ | `PROVIDERS_CHANGED` (Android) / authorization & services-disabled (iOS) → `LOCATION_OFF` event; auto-resume on `LOCATION_ON`. **No package UI** — app listens to the event and decides whether to show its own popup; `requestEnableLocation()` invokes the OS enable-location flow on demand |
| Permission revoked | ❌ | detect on resume → `PERMISSION_REVOKED` + re-prompt |
| Force-Stop from Settings | ❌ (OS kills alarms/jobs) | unstoppable at the instant; last `HEARTBEAT` + gap ⇒ attributable on next launch / server-side |
| Airplane / no network | ✅ capture, ⏸ upload | buffer, sync on reconnect (NetInfo) |
| Mock / fake GPS | can't block | `isFromMockProvider()` → `MOCK_DETECTED` + `mock:true` tag |
| Uninstall | ❌ | server sees check-ins stop |

---

## 7. Store compliance (build so it adds zero rejection risk)

### Google Play
- Package **never auto-requests** background permission → app shows prominent disclosure first, then calls `requestPermissions()`.
- Config plugin injects `foregroundServiceType="location"` + `FOREGROUND_SERVICE_LOCATION`; ships FGS-declaration justification text.
- Public APIs only (FusedLocation/WorkManager) — no kill-survival "tricks" that trip Play scanners.
- Docs include disclosure copy + demo-video script + exact data-collection field list (for Data Safety form).
- Minimum permission set only.

### Apple
- Config plugin **requires** specific purpose strings (no vague defaults; current app's "provide better services" is a known 2.5.4 rejection risk).
- Background mode `location` genuinely needed + app-driven consent; blue indicator on.
- Privacy nutrition labels from the documented field list.

### Distribution note
Lowest-rejection path for an employee tracker: Android via **Managed Google Play (private/internal)**, iOS via **Apple Business Manager custom app**. Package is identical either way.

---

## 8. Backend ingestion endpoint (new)

`POST /locations/batch`
- Auth: JWT (sales executive). Closes `".write": true` hole.
- Body: `{ points: [...], events: [...] }` (batched).
- Validate: drop `acc > 50`, tag/handle `mock`, dedupe by `(u,t)`.
- Write into `raw_locations` (same shape) → existing pipeline unchanged.
- Idempotent: safe to receive the same batch twice (device retries) — dedupe on `(u,t)` or a client-generated point id.
- Response: 200 with accepted ids (so device deletes exactly those rows).

---

## 9. Accuracy expectations (honest)
- **Positional** accuracy is hardware/environment-bound (~3–10 m open, 15–40 m urban, 50–300 m poor); the package makes it *known and filterable*, not better.
- **Distance** accuracy improves via completeness + road-snapping — typically ~5–15 % of odometer for road travel with continuous data; degrades with gaps/poor signal. **Calibrate with a known-route run; do not promise a fixed %.**

---

## 10. Testing & release isolation
- Develop/QA in `example/` on **real budget Android handsets** (Xiaomi/Oppo/Vivo/Realme) + an iPhone — never emulators for background behavior.
- CI builds Android + iOS on every PR; runs example smoke test.
- Semver; host app upgrades deliberately. Pinned `peerDependencies` (expo, react-native); New-Arch compatible.

---

## 11. Definition of done
- Survives swipe-kill + reboot on 3+ OEM devices for an 8-hour ride with < 2 % point loss.
- All Tier-2 events captured + delivered with last-known location.
- Offline-then-reconnect loses zero points.
- `acc` always present; mock fixes tagged.
- Clean prebuild + green CI on both platforms; example app demonstrates end-to-end to a local `/locations/batch`.
