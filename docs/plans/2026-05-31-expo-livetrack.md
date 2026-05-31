# expo-livetrack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone, store-compliant Expo native module that captures location reliably (survives OEM kills/reboot/offline), buffers on-device, and syncs batches + tamper events to a backend `POST /locations/batch` — fixing the missing-points root cause behind SIM Connect's jumping/distance issues.

**Architecture:** Native foreground service captures → writes to on-device SQLite → a native uploader POSTs batches to the backend with retry, deleting rows only on ACK. JS is a thin control/event surface. A config plugin injects all native manifest/Info.plist changes at prebuild. Downstream backend pipeline (`raw_locations` → consumer → cron) is unchanged.

**Tech Stack:** Expo Modules API (Kotlin/Swift), Android FusedLocationProvider + Room + WorkManager, iOS CLLocationManager + SQLite, TypeScript, NestJS (ingestion endpoint), Jest.

**Test strategy (read first):** When the app is killed, JS is dead — so capture/buffer/upload are native and not JS-unit-testable. Therefore:
- **Full TDD:** backend endpoint (Jest+supertest), config plugin (generated-file snapshot), TS API (mocked native), Android buffer DAO (Robolectric).
- **Real-device verification gates** (no unit test possible): foreground-service survival, reboot resume, Doze, offline-then-sync. These are explicit manual checklists at phase ends, run on real budget OEM handsets (Xiaomi/Oppo/Vivo/Realme) + an iPhone — never emulators for background behavior.
- Where exact native SDK signatures are needed, **verify against current docs** (Expo Modules API, Android `FusedLocationProviderClient`, `WorkManager`, `CLLocationManager`) before coding — do not guess versions.

---

## File Structure

```
expo-livetrack/
├── package.json, tsconfig.json, expo-module.config.json   (scaffold)
├── src/
│   ├── index.ts                 # public exports
│   ├── LiveTrack.ts            # JS API (start/stop/getState/requestPermissions/events)
│   ├── LiveTrack.types.ts      # Config, EventType, payload types
│   └── LiveTrackModule.ts      # requireNativeModule binding
├── android/src/main/
│   ├── java/.../LiveTrackModule.kt        # Expo module: bridges JS ↔ service
│   ├── java/.../TrackingService.kt         # foreground service (capture loop)
│   ├── java/.../buffer/PointEntity.kt      # Room entity (locations + events)
│   ├── java/.../buffer/PointDao.kt         # insert / unsynced / deleteByIds
│   ├── java/.../buffer/BufferDb.kt         # Room database
│   ├── java/.../sync/UploadWorker.kt       # WorkManager batch uploader
│   ├── java/.../keepalive/BootReceiver.kt  # BOOT_COMPLETED + MY_PACKAGE_REPLACED
│   ├── java/.../keepalive/Watchdog.kt      # AlarmManager re-spawn
│   ├── java/.../events/ProviderReceiver.kt # PROVIDERS_CHANGED → LOCATION_OFF/ON
│   └── AndroidManifest.xml                  # module manifest (service/receivers)
├── ios/
│   ├── LiveTrackModule.swift   # Expo module bridge
│   ├── TrackingManager.swift    # CLLocationManager + significant-change
│   ├── Buffer.swift             # SQLite buffer
│   └── Uploader.swift           # URLSession batch upload
├── plugin/src/index.ts          # config plugin (manifest/Info.plist/strings)
├── example/                     # Expo app for real-device QA
└── docs/                        # spec + compliance docs

backend (in sim_admin, separate task at the end):
└── src/modules/location/ingest/
    ├── ingest.controller.ts     # POST /locations/batch
    ├── ingest.service.ts        # validate + dedupe + write raw_locations
    └── ingest.dto.ts            # BatchDto
```

---

## Phase 0 — Scaffold the isolated package

### Task 0.1: Create the Expo module
**Files:** Create: whole `expo-livetrack/` package (excluding `docs/`, already present).

- [ ] **Step 1: Scaffold**

Run in `/Users/anandu/StudioProjects/expo-livetrack`:
```bash
npx create-expo-module@latest . --no-example
# When prompted: name "expo-livetrack", package "expo.modules.livetrack"
npx create-expo-module@latest example-tmp --no-example && mv example-tmp/example ./example 2>/dev/null || npx create-expo-module@latest --example
```
Expected: `android/`, `ios/`, `src/`, `expo-module.config.json` created. (If the scaffolder overwrites `docs/`, restore it from git.)

- [ ] **Step 2: Initialise git + pin peer deps**

Edit `package.json`: move `expo` and `react-native` to `peerDependencies` with ranges (`"expo": ">=54"`, `"react-native": ">=0.81"`). Set `"version": "0.1.0"`.

- [ ] **Step 3: Verify it builds in the example**
```bash
cd example && npx expo prebuild --clean && npx expo run:android
```
Expected: example app launches, default native module method callable.

- [ ] **Step 4: Commit**
```bash
git add -A && git commit -m "chore: scaffold expo-livetrack expo module"
```

---

## Phase 1 — TS API surface (TDD with mocked native)

### Task 1.1: Define types
**Files:** Create: `src/LiveTrack.types.ts`

- [ ] **Step 1: Write the types**
```ts
export type EventType =
  | 'LOCATION_OFF' | 'LOCATION_ON' | 'PERMISSION_REVOKED'
  | 'PERMISSION_GRANTED' | 'MOCK_DETECTED' | 'BATTERY_OPT_ON' | 'HEARTBEAT';

export interface Cadence {
  movingIntervalMs?: number;   // default 12000
  movingDistanceM?: number;    // default 30
  stillIntervalMs?: number;    // default 120000
  batchSize?: number;          // default 50
  maxAccuracyM?: number;       // default 50
}
export interface StartConfig { url: string; token: string; userId: string; cadence?: Cadence; }
export interface TrackerState {
  tracking: boolean; permission: 'granted'|'denied'|'background';
  locationServices: boolean; batteryOptIgnored: boolean; bufferedCount: number;
}
export interface LocationRecord { l:number; g:number; t:number; s:number; acc:number; b:number; c:boolean; act:string; mock:boolean; }
export interface TrackerEvent { e: EventType; t:number; l?:number; g?:number; b?:number; }
```

- [ ] **Step 2: Commit** `git add -A && git commit -m "feat(ts): tracker types"`

### Task 1.2: JS API with defaults (TDD)
**Files:** Create `src/LiveTrack.ts`, `src/LiveTrackModule.ts`, `src/index.ts`; Test `src/__tests__/LiveTrack.test.ts`

- [ ] **Step 1: Failing test** (mock the native module)
```ts
jest.mock('../LiveTrackModule', () => ({ default: { start: jest.fn(), stop: jest.fn(), getState: jest.fn() } }));
import native from '../LiveTrackModule';
import { LiveTracker } from '../LiveTrack';

test('start fills cadence defaults and forwards to native', async () => {
  await LiveTracker.start({ url:'u', token:'tok', userId:'x' });
  expect(native.start).toHaveBeenCalledWith(expect.objectContaining({
    url:'u', token:'tok', userId:'x',
    cadence: expect.objectContaining({ movingIntervalMs:12000, movingDistanceM:30, stillIntervalMs:120000, batchSize:50, maxAccuracyM:50 }),
  }));
});
```
- [ ] **Step 2: Run, verify FAIL** `npx jest LiveTrack -t "cadence defaults"` → FAIL (module undefined).
- [ ] **Step 3: Implement** `LiveTrackModule.ts` (`requireNativeModule('LiveTrack')`) and `LiveTrack.ts` merging defaults, exposing `start/stop/getState/requestPermissions/ensureNotKilled/requestEnableLocation/onLocation/onEvent/onSyncError` (event subscriptions via the module's `EventEmitter`). `requestEnableLocation(): Promise<boolean>` is a thin pass-through to native — **the package shows no popup itself**; the app calls it on demand (e.g. from its own `LOCATION_OFF` handler).
- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: Commit** `git commit -am "feat(ts): LiveTracker API with cadence defaults"`

---

## Phase 2 — Android buffer (TDD with Robolectric)

### Task 2.1: Room entity + DAO
**Files:** Create `android/.../buffer/PointEntity.kt`, `PointDao.kt`, `BufferDb.kt`; Test `android/src/test/.../PointDaoTest.kt`

- [ ] **Step 1: Failing DAO test (Robolectric, in-memory Room)**
```kotlin
@RunWith(RobolectricTestRunner::class)
class PointDaoTest {
  private lateinit var db: BufferDb; private lateinit var dao: PointDao
  @Before fun setup() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BufferDb::class.java).allowMainThreadQueries().build()
    dao = db.pointDao()
  }
  @After fun teardown() = db.close()

  @Test fun insert_then_unsynced_returns_in_time_order_then_delete_clears() {
    dao.insert(PointEntity(userId="u", t=2, lat=1.0, lng=1.0))
    dao.insert(PointEntity(userId="u", t=1, lat=1.0, lng=1.0))
    val batch = dao.unsynced(50)
    assertEquals(listOf(1L,2L), batch.map { it.t })
    dao.deleteByIds(batch.map { it.id })
    assertEquals(0, dao.unsynced(50).size)
  }
}
```
- [ ] **Step 2: Run, verify FAIL** `./gradlew :expo-livetrack:testDebugUnitTest --tests *PointDaoTest` → FAIL (classes missing).
- [ ] **Step 3: Implement** `PointEntity` (id auto, userId, t, lat, lng, speed, acc, batt, charging, act, mock, eventType nullable — one table for locations AND events), `PointDao` (`insert`, `@Query unsynced(limit) ORDER BY t ASC`, `deleteByIds`, `count()`), `BufferDb`.
- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: Commit** `git commit -am "feat(android): Room buffer entity + DAO"`

---

## Phase 3 — Android capture service (real-device gate)

### Task 3.1: Foreground service capture loop
**Files:** Create `android/.../TrackingService.kt`, modify module `AndroidManifest.xml`.

- [ ] **Step 1: Implement service skeleton** — `onStartCommand` returns `START_STICKY`; `startForeground(notif, FOREGROUND_SERVICE_TYPE_LOCATION)`; request `FusedLocationProviderClient` updates with `Priority.HIGH_ACCURACY`, `setMinUpdateIntervalMillis(movingInterval)`, `setMinUpdateDistanceMeters(movingDistance)`. In the callback build a `PointEntity` (always read `location.accuracy`, `location.speed`, `location.isFromMockProvider`) and `dao.insert(...)`. *(Verify exact `LocationRequest.Builder` API against current Play Services docs.)*
- [ ] **Step 2: Manifest** — add to module `AndroidManifest.xml`:
```xml
<service android:name=".TrackingService" android:exported="false" android:foregroundServiceType="location" />
```
- [ ] **Step 3: Wire module** — `LiveTrackModule.kt` `Function("start")` persists config (SharedPreferences) and `ContextCompat.startForegroundService(...)`; `Function("stop")` stops it.
- [ ] **Step 4: REAL-DEVICE VERIFICATION** (no unit test): on a physical phone, `start()`, lock screen, walk ~300 m. Then `adb shell run-as ... sqlite3` (or a debug `getState().bufferedCount`) shows points accumulating with non-null `acc`. **Gate: points captured with accuracy while screen off.**
- [ ] **Step 5: Commit** `git commit -am "feat(android): foreground capture service → buffer"`

### Task 3.2: Motion-adaptive cadence + heartbeat
- [ ] **Step 1:** Add Activity Recognition (`ActivityRecognitionClient`) → when `still`, switch request to `stillIntervalMs`; on movement, restore. Insert a `HEARTBEAT` event row every `stillIntervalMs` when still. Set `act` on each point. *(Verify ActivityRecognition transitions API.)*
- [ ] **Step 2: REAL-DEVICE GATE:** sit still 5 min → buffer shows ~1 point/2 min + heartbeats, not a flood. Drive → cadence increases.
- [ ] **Step 3: Commit** `git commit -am "feat(android): motion-adaptive cadence + heartbeat"`

---

## Phase 4 — Android sync (uploader)

### Task 4.1: UploadWorker batch POST
**Files:** Create `android/.../sync/UploadWorker.kt`; Test `android/src/test/.../UploadWorkerTest.kt`

- [ ] **Step 1: Failing test** (Robolectric + `TestListenableWorkerBuilder`, mock web server): seed 3 unsynced rows; run worker against a MockWebServer returning 200 with accepted ids; assert rows deleted and `Result.success()`. On 500, assert rows kept and `Result.retry()`.
- [ ] **Step 2: Run, verify FAIL.**
- [ ] **Step 3: Implement** — read `dao.unsynced(batchSize)`, POST `{points, events}` with `Authorization: Bearer <token>` to `url`; on 2xx `dao.deleteByIds(acceptedIds)` + `Result.success()`; on network/5xx `Result.retry()` (WorkManager backoff). Enqueue as a unique periodic + expedited work from the service after each insert batch; constrain to `NetworkType.CONNECTED`.
- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: REAL-DEVICE GATE:** airplane mode on → capture 5 pts (buffer grows); airplane off → buffer drains to backend within one work cycle. **Zero loss.**
- [ ] **Step 6: Commit** `git commit -am "feat(android): WorkManager batch uploader with ACK-delete"`

---

## Phase 5 — Android keep-alive & Tier-2 events

### Task 5.1: Watchdog + BootReceiver
**Files:** Create `keepalive/Watchdog.kt`, `keepalive/BootReceiver.kt`; modify manifest.
- [ ] **Step 1:** `Watchdog` schedules `AlarmManager.setExactAndAllowWhileIdle` (~15 min) → checks if service alive, restarts if not. `BootReceiver` on `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` restarts the service if persisted state says "was tracking". Manifest: register receiver with those intent filters + `RECEIVE_BOOT_COMPLETED` permission.
- [ ] **Step 2: REAL-DEVICE GATES:** (a) swipe app from recents → within watchdog window, capture resumes. (b) reboot phone → tracking auto-resumes. (c) on an OEM device (Xiaomi/Oppo), with autostart enabled, same holds.
- [ ] **Step 3: Commit** `git commit -am "feat(android): watchdog + boot resume"`

### Task 5.2: Provider/permission/battery events
**Files:** Create `events/ProviderReceiver.kt`; logic in module.
- [ ] **Step 1:** Register `ProviderReceiver` for `android.location.PROVIDERS_CHANGED` → insert `LOCATION_OFF`/`LOCATION_ON` event rows (with last-known location). On `start`/resume, check `checkSelfPermission` → `PERMISSION_REVOKED`/`GRANTED`. Periodic `isIgnoringBatteryOptimizations()` → `BATTERY_OPT_ON` when re-enabled. `requestPermissions()` and `ensureNotKilled()` (battery-opt intent + `expo-intent-launcher` OEM autostart deep-links) exposed to JS.
- [ ] **Step 1b: `requestEnableLocation()` (app-controlled, NO package UI)** — implement a `Function("requestEnableLocation")` that fires `SettingsClient.checkLocationSettings(LocationSettingsRequest)`; on `ResolvableApiException`, launch the **OS** "turn on location?" dialog via the activity and resolve `true/false` from the result. The package shows no popup of its own — it only exposes this method + emits `LOCATION_OFF`; the host app decides when/whether to call it. *(Verify SettingsClient resolution API.)*
- [ ] **Step 2: REAL-DEVICE GATE:** toggle GPS off/on, revoke permission → corresponding event rows appear and reach backend with last-known location; calling `requestEnableLocation()` from JS shows the native enable-location dialog and resumes on accept.
- [ ] **Step 3: Commit** `git commit -am "feat(android): tier-2 tamper events"`

---

## Phase 6 — iOS parity (real-device gate)

### Task 6.1: CLLocationManager capture + buffer + upload
**Files:** Create `ios/TrackingManager.swift`, `Buffer.swift`, `Uploader.swift`, `LiveTrackModule.swift`.
- [ ] **Step 1:** `CLLocationManager`: `allowsBackgroundLocationUpdates=true`, `pausesLocationUpdatesAutomatically=false`, `desiredAccuracy=Best`, `startUpdatingLocation()` + `startMonitoringSignificantLocationChanges()` (relaunch after termination). Delegate writes each `CLLocation` (coordinate, horizontalAccuracy→acc, speed) to a SQLite buffer; `URLSession` uploader batches like Android. `didChangeAuthorization` / services-disabled → `LOCATION_OFF`/`PERMISSION_REVOKED` events. `requestEnableLocation()` deep-links to Settings via `UIApplication.openSettingsURLString` (iOS forbids programmatic toggling) and returns — again, **no package UI**; the app owns its popup. *(Verify background-location entitlement + significant-change relaunch behavior against current Core Location docs.)*
- [ ] **Step 2: REAL-DEVICE GATE (iPhone):** background the app, move → points captured; force-quit → significant-change relaunches and resumes; offline→online drains buffer.
- [ ] **Step 3: Commit** `git commit -am "feat(ios): capture + buffer + upload parity"`

---

## Phase 7 — Config plugin (TDD via generated-file snapshot)

### Task 7.1: Plugin injects native config
**Files:** Create `plugin/src/index.ts`; Test `plugin/src/__tests__/plugin.test.ts`
- [ ] **Step 1: Failing test** — run the plugin against a fixture Expo config; assert the modified `AndroidManifest` contains the `<service foregroundServiceType="location">`, `BootReceiver`, `ProviderReceiver`, and permissions (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `RECEIVE_BOOT_COMPLETED`, `ACTIVITY_RECOGNITION`); and that `Info.plist` gets `UIBackgroundModes:[location]` + the **caller-provided** `NSLocation*` strings (no defaults).
```ts
test('throws if iOS purpose strings missing', () => {
  expect(() => withLiveTrack(baseConfig, {} as any)).toThrow(/purpose string/i);
});
```
- [ ] **Step 2: Run, verify FAIL.**
- [ ] **Step 3: Implement** using `@expo/config-plugins` (`withAndroidManifest`, `withInfoPlist`, `AndroidConfig.Permissions`); require `locationWhenInUse` + `locationAlways` strings in plugin props, throw if absent.
- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: VERIFY in example** — add plugin to `example/app.json` with strings → `npx expo prebuild --clean` → grep generated manifest/Info.plist for the entries.
- [ ] **Step 6: Commit** `git commit -am "feat(plugin): inject native config + require iOS purpose strings"`

---

## Phase 8 — Dummy ingestion test server (IN-PACKAGE, NOT sim_admin)

> **Decision:** Do NOT modify `sim_admin`. For validation, build a self-contained mock ingestion server **inside the tracker repo** (`test-server/`) that mirrors the real `/locations/batch` contract. This lets us run + curl the endpoint and validate the capture→buffer→upload→ACK flow in total isolation. The real production endpoint in `sim_admin` is a separate, later effort the team owns.

### Task 8.1: Dummy `/locations/batch` server + contract tests (full TDD)
**Files:** Create `test-server/server.ts` (or `.js`), `test-server/ingest.ts` (validate/dedupe logic), `test-server/package.json`; Test `test-server/__tests__/ingest.test.ts`
- [ ] **Step 1: Failing test** (Jest+supertest): POST a batch with one `acc=80` point, one `acc=12` point, a duplicate `(u,t)`, and one event row → assert response 200 with `acceptedIds` containing only the valid+deduped point ids (and the event), and that an in-memory store now holds exactly those records. Missing/invalid `Authorization` → 401.
- [ ] **Step 2: Run, verify FAIL.**
- [ ] **Step 3: Implement** — tiny Express server: `POST /locations/batch` (Bearer-token check, any non-empty token accepted for the dummy), body `{points[], events[]}`; `ingest.ts` validates (drop `acc>50`), tags `mock`, dedupes by `(u,t)`, stores in memory, returns `{ acceptedIds }`. Add `GET /debug/received` to inspect everything received (for manual curl validation), and `POST /debug/reset`. Same response contract the native uploader expects (delete-on-ACK by returned ids).
- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: Manual validation** — start the server (`npm --prefix test-server start`), `curl` a sample batch, confirm `acceptedIds` + `GET /debug/received` reflect the dedupe/accuracy filtering. Capture the curl output.
- [ ] **Step 6: Commit** `git commit -am "feat(test-server): dummy /locations/batch for isolated validation"`

---

## Phase 9 — Example app + release

### Task 9.1: End-to-end demo + docs
- [ ] **Step 1:** In `example/`, a screen: login stub → prominent-disclosure modal → `requestPermissions()` → `ensureNotKilled()` → `start({url, token, userId})`; live `getState()` + `onEvent` log. Point `url` at the in-package **`test-server`** `/locations/batch` (e.g. `http://<LAN-ip>:8787/locations/batch`) — NOT sim_admin. Use `GET /debug/received` on the test-server to confirm the device's points/events arrived. **Demonstrate the app-controlled enable-location popup:** subscribe to `onEvent` and, on `LOCATION_OFF`, show an *app-defined* `Alert`/modal (copy + styling owned by the example app, not the package) whose CTA calls `LiveTracker.requestEnableLocation()`. This proves the popup is fully configurable from app code.
- [ ] **Step 2: FULL E2E REAL-DEVICE GATE (Definition of Done):** on 3+ OEM handsets + iPhone, 8-hour ride with swipe-kill + reboot mid-ride → backend receives a continuous track with < 2% point loss; all Tier-2 events delivered with last-known location; offline segment loses zero points.
- [ ] **Step 3:** Write `README.md` (install, plugin config with purpose strings, API) + compliance docs (disclosure copy, FGS justification text, demo-video script, data-collection field list).
- [ ] **Step 4: CI** — GitHub Actions: lint + jest + `expo prebuild` Android & iOS build.
- [ ] **Step 5: Commit + tag** `git commit -am "feat: example app, docs, CI" && git tag v0.1.0`

---

## Self-Review (completed)
- **Spec coverage:** capture/buffer/sync (P2–4,6), keep-alive (P5.1), Tier-2 events + schema (P5.2,6), store compliance/config plugin (P7), backend endpoint + RTDB lock (P8), isolation/CI/example (P0,9), accuracy expectations (DoD gate P9.2). Non-goals (frontend map, live-vs-nightly reconciliation) correctly excluded.
- **Placeholder scan:** native phases intentionally use skeleton-plus-real-device-gates (justified in Test Strategy) rather than fabricated full Kotlin/Swift; deterministic phases (TS, DAO, uploader, plugin, backend) are strict TDD with concrete tests.
- **Type consistency:** `start/stop/getState/requestPermissions/ensureNotKilled/requestEnableLocation`, `unsynced/deleteByIds`, event names, and the `{u,l,g,t,s,acc,b,c,act,mock}` + `e` schema match across TS, Android, and backend tasks.
- **Enable-location popup:** app-controlled by design — package emits `LOCATION_OFF` + exposes `requestEnableLocation()`; renders no UI itself (spec §4, tasks 1.2, 5.2, 6.1, 9.1).
