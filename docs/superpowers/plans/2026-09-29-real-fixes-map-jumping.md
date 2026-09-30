# Real Fixes for Map-Jumping: Still-State Stall + Background Kill

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship durable fixes for both root causes of "map jumping" confirmed by 7 days of field-data analysis (2026-09-22 through 09-29, user 90eb4447-27f1-43e2-bb1d-31e21c56fe16): (1) the location-update stream stalling for 20–100+ minutes while the device is classified `STILL`, and (2) the background service being killed by the OS/OEM (heartbeats and location both go silent together).

**Architecture:** Issue #1 already has a substantial, well-designed, uncommitted fix sitting in the working tree (a `LocationGate` noise gate + a still-mode location-request change) — that work just needs review and a clean, scoped commit. Issue #2 needs new work: the package's own approved design spec (`docs/2026-05-31-location-tracker-design.md`) already calls for `ensureNotKilled()` to prompt OEM-specific autostart/protected-app settings alongside the battery-optimization dialog, and that half was never built. This plan adds it as a separate one-time onboarding prompt (confirmed with the user) rather than folding it into the existing cooldown/cap loop, since autostart grant/revocation isn't programmatically detectable the way `isIgnoringBatteryOptimizations()` is.

**Tech Stack:** Kotlin (Android), Expo Modules API, TypeScript/React Native, JUnit (plain, no-Android-import tests following the `LocationGate` precedent).

**Spec:** `expo-livetrack/docs/2026-05-31-location-tracker-design.md` — the approved design doc whose RCA (line 12) names OEM background-kill as "#1 cause of map jumping" and whose API section (line 90) specifies `ensureNotKilled()` as "battery-opt + OEM autostart prompts". This plan closes the gap between that spec and what's actually implemented.

## Global Constraints

- **Branch hygiene (critical, learned the hard way earlier this session):** `fix/location-gate` has substantial pre-existing uncommitted work from an unrelated, separate diagnostic/archive-export feature mixed into several shared files (`LiveTrackModule.kt`, `Prefs.kt`, `BufferDb.kt`, `PointDao.kt`, `sync/UploadWorker.kt`, `src/LiveTrack.ts`, `src/LiveTrack.types.ts`, plus new `archive/`, `sync/TokenProviders.kt`, `sync/UploadClient.kt`, and their tests). **None of that is in scope for this plan.** Every task below that touches a shared file MUST stage only its own hunks via `git add -p` (or split-hunk `s`/edit `e` mode) and run `git diff --staged` to confirm the staged diff contains ONLY this task's changes before committing — exactly the procedure already used successfully for the `ensureNotKilled()` fix earlier this session.
- Confirmed already fully separable (verified via `git diff` in this planning session): `TrackingService.kt`, `capture/LocationGate.kt` (new), `test/capture/LocationGateTest.kt` (new), `BootReceiver.kt`, `LiveTrackServiceController.kt`, `ProviderReceiver.kt` contain ONLY still-cadence/kill-infra work — no archive-export content. These can be committed as whole-file diffs for Task 1.
- Confirmed NOT separable cleanly and NOT in scope: `LiveTrackModule.kt` (100% archive-export: `exportArchive`/`replay`/`getCoverage`/`clearArchive`/`getArchiveStats`), `Prefs.kt` (100% archive-export: `KEY_ARCHIVE_DAYS`/`KEY_DIAGNOSTICS_LIFECYCLE`), `BufferDb.kt`, `buffer/PointDao.kt`, `sync/UploadWorker.kt` (100% archive-export). Tasks 2–3 below add NEW hunks to some of these already-dirty files (`LiveTrackModule.kt`, `src/LiveTrack.ts`, `src/LiveTrack.types.ts`) — stage only the new hunks.
- iOS is out of scope for this plan: neither the still-cadence stall nor OEM-autostart-style background kills are Android-OEM-specific concepts on iOS (iOS's `ensureNotKilled` is already a no-op per earlier work this session).
- Android-only manufacturers to support for autostart (per the design doc's own named QA devices, `docs/2026-05-31-location-tracker-design.md:181`): Xiaomi/Redmi/POCO (MIUI), Oppo (ColorOS), Vivo/iQOO (FuntouchOS/OriginOS), Realme (realme UI). Huawei/Honor optional/nice-to-have, not required.
- Follow existing conventions throughout: `runCatching {}.onFailure {}` / try-catch-degrade-gracefully error handling (never crash the host app over a best-effort OEM settings deep-link), `AsyncFunction`/`Promise` pattern, hand-built `Bundle`/`Map` returns (no `Record` types), `expo-intent-launcher` for JS-side intent launches (already used in `LocationContext.tsx` for the battery-optimization settings screen).

---

## Context

Seven days of field-data analysis (Supabase Storage `travel_data` bucket exports + Firebase RTDB `tracker_events`, both for user `90eb4447-27f1-43e2-bb1d-31e21c56fe16`) found 142 gaps >5 minutes across 7 days, splitting roughly 58%/42% between two distinct mechanisms:

- **Still-state stall (58%):** heartbeats fire at normal cadence throughout the gap (proving the process is alive), but no location fix arrives — sometimes for over 100 minutes — while activity is classified `STILL`.
- **Background kill (42%):** heartbeats *also* go silent for a stretch comparable to the location gap, then both resume together — the process itself was down.

Investigating the native module for the still-state stall turned up an already-written, unit-tested (but uncommitted) fix: `TrackingService.buildLocationRequest()`'s comment (`TrackingService.kt:306-312`) explains the old bug directly — a distance floor kept on `minUpdateDistanceMeters` even while still meant a genuinely parked device could never clear it, so no further fix ever arrived until real movement resumed past the threshold. The new `LocationGate` (`capture/LocationGate.kt`) now also emits a `Snap` keepalive — a real location row at the anchor, not just an event — at least once per `stillIntervalMs` while stationary. This directly closes the gap class we measured.

For the background-kill issue, `keepalive/Watchdog.kt` (a 15-minute `AlarmManager` restart alarm) is already committed and working, alongside `START_STICKY` and `BootReceiver`'s boot/update handling. What's still missing, per the package's own approved design doc, is the OEM autostart/protected-apps prompt — Android gives foreground services no protection against OEM-specific "battery savers" (MIUI/ColorOS/FuntouchOS/realme UI) that kill apps not explicitly whitelisted in their own separate autostart manager, entirely outside the standard `isIgnoringBatteryOptimizations()` mechanism this session's earlier `ensureNotKilled()` fix already handles.

## Files to Change

| File | Change |
|---|---|
| `expo-livetrack/android/.../TrackingService.kt`, `capture/LocationGate.kt`, `test/capture/LocationGateTest.kt`, `BootReceiver.kt`, `LiveTrackServiceController.kt`, `ProviderReceiver.kt` | Task 1: review + scoped commit of already-written still-cadence fix (no new code) |
| `expo-livetrack/android/.../oem/OemAutostart.kt` (new) | Task 2: pure manufacturer→intent lookup table (testable, no Android imports, mirrors `LocationGate`'s style) |
| `expo-livetrack/android/.../LiveTrackModule.kt` | Task 2: new `AsyncFunction("getOemAutostartInfo")` + `AsyncFunction("openAutostartSettings")` |
| `expo-livetrack/android/src/test/.../oem/OemAutostartTest.kt` (new) | Task 2: JUnit test for the lookup table |
| `expo-livetrack/src/LiveTrack.types.ts`, `LiveTrack.ts` | Task 3: `OemAutostartInfo` type + two JS wrappers |
| `react_native_basic_structure/src/contexts/LocationContext.tsx` | Task 3: one-time onboarding prompt, gated on a persisted flag |

---

### Task 1: Review and land the already-written still-cadence fix

**Files:**
- Modify (review only, no changes expected): `expo-livetrack/android/src/main/java/expo/modules/livetrack/TrackingService.kt`, `BootReceiver.kt`, `LiveTrackServiceController.kt`, `ProviderReceiver.kt`
- New (review only): `expo-livetrack/android/src/main/java/expo/modules/livetrack/capture/LocationGate.kt`, `expo-livetrack/android/src/test/java/expo/modules/livetrack/capture/LocationGateTest.kt`

**Interfaces:**
- Consumes: nothing new — this is the current uncommitted working-tree state.
- Produces: `LocationGate<T>` (`evaluate`/`flush`, `Outcome` sealed class: `Reject`/`Hold`/`Release`/`Snap`) and `TrackingService`'s still-mode `buildLocationRequest(intervalMs, still)` — both already exist; later tasks do not depend on any new symbol from this task.

- [ ] **Step 1: Confirm the diff is exactly these 6 files, nothing else**

Run: `git status --short` and `git diff HEAD --stat -- android/src/main/java/expo/modules/livetrack/TrackingService.kt android/src/main/java/expo/modules/livetrack/BootReceiver.kt android/src/main/java/expo/modules/livetrack/LiveTrackServiceController.kt android/src/main/java/expo/modules/livetrack/ProviderReceiver.kt`
Expected: stat output matches this plan's Global Constraints table (TrackingService.kt ~229 lines, BootReceiver.kt ~8, LiveTrackServiceController.kt ~10, ProviderReceiver.kt ~30), plus the two new untracked files under `capture/`.

- [ ] **Step 2: Read all 6 files end to end and self-review as if this were your own PR**

Check specifically:
- `TrackingService.kt`: does `onEnterStill()`/`onExitStill()` correctly flush the gate, restart location updates, and manage the heartbeat handler with no leaked callbacks (`heartbeatHandler.removeCallbacks` before every `postDelayed`)? Does `onDestroy()` unregister everything it registered in `onStartCommand`/`onCreate`?
- `LocationGate.kt`: does `evaluate()` handle every `Outcome` case the caller (`TrackingService.handleLocation`) switches on? Cross-check `MAX_SPEED_MPS`, `REANCHOR_COUNT`, `ZIGZAG_LEG_M` etc. against `LocationGateTest.kt`'s actual test cases — do the tests exercise the teleport re-anchor path and the zig-zag rejection path, not just the happy path?
- `ProviderReceiver.kt`: confirm the `DEBOUNCE_LOCK` synchronized block actually eliminates the described race (read-compare-write must be inside the same lock as the write — check it is).
- `BootReceiver.kt` / `LiveTrackServiceController.kt`: confirm `reason` threads correctly from `Watchdog`/`BootReceiver` through `buildStartIntent` into `TrackingService.EXTRA_START_REASON`.

- [ ] **Step 3: Run the existing unit tests**

Run: `cd react_native_basic_structure/android && ./gradlew :expo-livetrack:testDebugUnitTest -I ../gradle-init/kapt-tmpdir.init.gradle`
Expected: `BUILD SUCCESSFUL`, `LocationGateTest` passes (this was already confirmed once earlier this session — re-run to catch any drift).

- [ ] **Step 4: Full release build**

Run: `cd react_native_basic_structure/android && ./gradlew assembleRelease -I ../gradle-init/kapt-tmpdir.init.gradle`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Stage and commit exactly these 6 files**

```bash
cd expo-livetrack
git add android/src/main/java/expo/modules/livetrack/TrackingService.kt \
        android/src/main/java/expo/modules/livetrack/BootReceiver.kt \
        android/src/main/java/expo/modules/livetrack/LiveTrackServiceController.kt \
        android/src/main/java/expo/modules/livetrack/ProviderReceiver.kt \
        android/src/main/java/expo/modules/livetrack/capture/ \
        android/src/test/java/expo/modules/livetrack/capture/
git diff --staged --stat   # confirm ONLY these 6 paths, no archive-export files
git commit -m "fix: motion-adaptive still-mode location requests via LocationGate

Fixes the #1 field-observed cause of map jumping: a distance floor kept
on minUpdateDistanceMeters even while classified STILL meant a genuinely
parked device never received another fix until real movement exceeded
the threshold, producing silent gaps of 20-100+ minutes. LocationGate
now emits a Snap keepalive location row at least once per stillIntervalMs
while stationary, and the still-mode LocationRequest drops the distance
floor to 0."
```

- [ ] **Step 6: Verify `git status` afterward shows the same other-feature files untouched**

Run: `git status --short` — the archive-export feature's files (`LiveTrackModule.kt`, `Prefs.kt`, `BufferDb.kt`, `buffer/PointDao.kt`, `sync/UploadWorker.kt`, new `archive/`/`sync/TokenProviders.kt`/`sync/UploadClient.kt`, JS `src/LiveTrack*.ts`) must still show as modified/untracked, exactly as before this task.

---

### Task 2: Native OEM-autostart lookup + native methods

**Files:**
- Create: `expo-livetrack/android/src/main/java/expo/modules/livetrack/oem/OemAutostart.kt`
- Create: `expo-livetrack/android/src/test/java/expo/modules/livetrack/oem/OemAutostartTest.kt`
- Modify: `expo-livetrack/android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt` (add two `AsyncFunction`s — this file already has substantial unrelated uncommitted content from the archive-export feature; stage ONLY your new hunk)

**Interfaces:**
- Produces: `OemAutostart.candidatesFor(manufacturer: String): List<ComponentName>` (pure, testable, empty list = unknown manufacturer) and `OemAutostart.isKnownAggressiveOem(manufacturer: String): Boolean`. `LiveTrackModule` adds `AsyncFunction("getOemAutostartInfo")` returning `Map<String, Any?>` with keys `manufacturer: String`, `hasKnownSettings: Boolean`, and `AsyncFunction("openAutostartSettings")` returning `Map<String, Any?>` with keys `attempted: Boolean`, `manufacturer: String`. Task 3 consumes both exact method names and both exact key sets.

- [ ] **Step 1: Write `OemAutostart.kt`**

Mirror `LocationGate.kt`'s style (pure Kotlin, no Android imports where possible — `ComponentName` needs `android.content.ComponentName` but nothing else Android-specific, so this stays plain-JUnit-testable):

```kotlin
package expo.modules.livetrack.oem

import android.content.ComponentName

/**
 * Best-effort lookup of OEM-specific "autostart"/"protected apps" settings
 * screens. Android gives foreground services no protection against these —
 * they sit entirely outside isIgnoringBatteryOptimizations() and are the
 * #1 field-observed cause of background kills (see
 * docs/2026-05-31-location-tracker-design.md). Component names are
 * undocumented and drift across ROM versions, hence the ordered candidate
 * list per manufacturer: the caller tries each in order and falls back to
 * the generic app-info screen if all fail.
 */
object OemAutostart {

  private val CANDIDATES: Map<String, List<ComponentName>> = mapOf(
    "xiaomi" to listOf(
      ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    "oppo" to listOf(
      ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
      ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
      ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    ),
    "vivo" to listOf(
      ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
      ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
      ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManagerActivity"),
    ),
    "realme" to listOf(
      ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
      ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    ),
  )

  /** @param manufacturer typically `Build.MANUFACTURER`, case-insensitive. */
  fun candidatesFor(manufacturer: String): List<ComponentName> =
    CANDIDATES[manufacturer.trim().lowercase()] ?: emptyList()

  fun isKnownAggressiveOem(manufacturer: String): Boolean =
    candidatesFor(manufacturer).isNotEmpty()
}
```

- [ ] **Step 2: Write `OemAutostartTest.kt`**

```kotlin
package expo.modules.livetrack.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemAutostartTest {

  @Test
  fun `known manufacturers return non-empty candidates`() {
    for (m in listOf("Xiaomi", "xiaomi", "OPPO", "vivo", "Realme")) {
      assertTrue("expected candidates for $m", OemAutostart.candidatesFor(m).isNotEmpty())
    }
  }

  @Test
  fun `unknown manufacturer returns empty list`() {
    assertEquals(emptyList<Any>(), OemAutostart.candidatesFor("Google"))
    assertEquals(emptyList<Any>(), OemAutostart.candidatesFor("samsung"))
  }

  @Test
  fun `isKnownAggressiveOem matches candidatesFor emptiness`() {
    assertTrue(OemAutostart.isKnownAggressiveOem("xiaomi"))
    assertFalse(OemAutostart.isKnownAggressiveOem("google"))
  }

  @Test
  fun `lookup is case-insensitive and trims whitespace`() {
    assertTrue(OemAutostart.isKnownAggressiveOem("  XIAOMI  "))
  }
}
```

- [ ] **Step 3: Run the new test in isolation**

Run: `cd react_native_basic_structure/android && ./gradlew :expo-livetrack:testDebugUnitTest --tests "expo.modules.livetrack.oem.OemAutostartTest" -I ../gradle-init/kapt-tmpdir.init.gradle`
Expected: 4/4 passing.

- [ ] **Step 4: Add the two `AsyncFunction`s to `LiveTrackModule.kt`**

Find the existing `requestEnableLocation` `AsyncFunction` block (added the same session as `ensureNotKilled`) and insert these two new functions immediately after it — do not touch anything else in this file:

```kotlin
    // getOemAutostartInfo(): cheap, side-effect-free check for whether this
    // device's manufacturer is a known aggressive background-killer with its
    // own separate "autostart"/"protected apps" allowlist outside Android's
    // standard battery-optimization exemption. The host app uses this to
    // decide whether to show the one-time onboarding prompt at all.
    AsyncFunction("getOemAutostartInfo") { promise: Promise ->
      runCatching {
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        mapOf(
          "manufacturer" to manufacturer,
          "hasKnownSettings" to expo.modules.livetrack.oem.OemAutostart.isKnownAggressiveOem(manufacturer),
        )
      }.onSuccess { promise.resolve(it) }
        .onFailure { promise.reject("ERR_OEM_AUTOSTART_INFO", it.message, it) }
    }

    // openAutostartSettings(): best-effort launch of this OEM's autostart
    // settings screen. Tries each known candidate ComponentName in order
    // (they drift across ROM versions) and falls back to the generic
    // app-info screen so the user always lands somewhere actionable rather
    // than seeing nothing happen.
    AsyncFunction("openAutostartSettings") { promise: Promise ->
      runCatching {
        val ctx = context
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val candidates = expo.modules.livetrack.oem.OemAutostart.candidatesFor(manufacturer)
        var attempted = false
        for (component in candidates) {
          val intent = Intent().apply {
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          if (runCatching { ctx.startActivity(intent) }.isSuccess) {
            attempted = true
            break
          }
        }
        if (!attempted) {
          // Fallback: generic app-info screen, always resolvable.
          val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"),
          ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
          runCatching { ctx.startActivity(fallback) }
        }
        mapOf("attempted" to attempted, "manufacturer" to manufacturer)
      }.onSuccess { promise.resolve(it) }
        .onFailure { promise.reject("ERR_OEM_AUTOSTART_OPEN", it.message, it) }
    }
```

- [ ] **Step 5: Build-verify**

Run: `cd react_native_basic_structure/android && ./gradlew assembleRelease -I ../gradle-init/kapt-tmpdir.init.gradle`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Stage only this task's hunks and commit**

```bash
cd expo-livetrack
git add android/src/main/java/expo/modules/livetrack/oem/ android/src/test/java/expo/modules/livetrack/oem/
git add -p android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt   # stage ONLY the two new AsyncFunctions
git diff --staged   # confirm no exportArchive/replay/getCoverage/clearArchive/getArchiveStats hunks are staged
git commit -m "feat: add OEM autostart-settings detection and launcher"
```

---

### Task 3: JS wrapper + one-time onboarding prompt

**Files:**
- Modify: `expo-livetrack/src/LiveTrack.types.ts`, `expo-livetrack/src/LiveTrack.ts` (both already have unrelated uncommitted archive-export content; stage only your new hunks)
- Modify: `react_native_basic_structure/src/contexts/LocationContext.tsx`

**Interfaces:**
- Consumes: `LiveTrackModule.getOemAutostartInfo()` → `{manufacturer: string, hasKnownSettings: boolean}`, `LiveTrackModule.openAutostartSettings()` → `{attempted: boolean, manufacturer: string}` (both from Task 2, exact shapes).
- Produces: `LiveTracker.getOemAutostartInfo(): Promise<OemAutostartInfo>`, `LiveTracker.openAutostartSettings(): Promise<OemAutostartResult>` for the app layer to call.

- [ ] **Step 1: Add types to `LiveTrack.types.ts`**

```ts
/** Result of `getOemAutostartInfo()`: whether this device needs an autostart prompt. */
export interface OemAutostartInfo {
  manufacturer: string;
  hasKnownSettings: boolean;
}

/** Result of `openAutostartSettings()`: whether a known OEM screen was reached. */
export interface OemAutostartResult {
  attempted: boolean;
  manufacturer: string;
}
```

- [ ] **Step 2: Add wrappers to `LiveTrack.ts`**

Add to the `LiveTracker` object, near `ensureNotKilled`:

```ts
  /**
   * Whether this device's manufacturer is a known aggressive background
   * killer (MIUI/ColorOS/FuntouchOS/realme UI) with its own separate
   * autostart allowlist outside the standard battery-optimization exemption.
   * Side-effect-free — use to decide whether to show an autostart prompt.
   */
  getOemAutostartInfo(): Promise<OemAutostartInfo> {
    return LiveTrackModule.getOemAutostartInfo();
  },

  /**
   * Best-effort launch of this OEM's autostart settings screen. Falls back
   * to the generic app-info screen if no known screen could be opened.
   */
  openAutostartSettings(): Promise<OemAutostartResult> {
    return LiveTrackModule.openAutostartSettings();
  },
```

Add `OemAutostartInfo`, `OemAutostartResult` to the type-only import list at the top of the file.

- [ ] **Step 3: Rebuild the compiled output**

Run: `cd expo-livetrack && npm run prepare`
Expected: succeeds, `build/LiveTrack.js`/`build/LiveTrack.types.js` regenerated with the new exports (gitignored, but the app's Metro bundler resolves from here per `metro.config.js`).

- [ ] **Step 4: Stage only the new hunks in the two TS files and commit**

```bash
git add -p src/LiveTrack.types.ts src/LiveTrack.ts   # stage ONLY OemAutostartInfo/OemAutostartResult + the two new wrapper methods
git diff --staged
git commit -m "feat: expose OEM autostart detection/launch to JS"
```

- [ ] **Step 5: Add the one-time onboarding prompt in `LocationContext.tsx`**

Add a new persisted-flag constant near the top of the file (matching the existing `storage` service pattern used by `trackingLog.ts`'s `KEY_CURSOR`):

```ts
/** AsyncStorage key: has the OEM autostart prompt already been shown once (ever)? */
const KEY_OEM_AUTOSTART_PROMPT_SHOWN = 'oem_autostart_prompt_shown';
```

Add a new one-time effect, modeled on the existing "Background Usage Restricted" effect (`LocationContext.tsx:441-469`) but firing only once ever, not on a 10-minute repeat cadence:

```tsx
  // One-time OEM autostart prompt: some manufacturers (MIUI/ColorOS/FuntouchOS/
  // realme UI) kill background services outside Android's standard battery-
  // optimization exemption entirely, via their own separate "autostart"
  // allowlist. Unlike battery optimization there is no API to detect whether
  // it's already granted or later revoked, so this is shown at most once ever
  // (persisted flag) rather than throttled/capped like ensureNotKilled().
  useEffect(() => {
    if (
      Platform.OS !== 'android' ||
      !hasBackgroundPermission ||
      !user
    ) {
      return;
    }
    let cancelled = false;
    (async () => {
      const alreadyShown = await storage.getItem<boolean>(KEY_OEM_AUTOSTART_PROMPT_SHOWN);
      if (alreadyShown || cancelled) return;
      const info = await LiveTracker.getOemAutostartInfo().catch(() => null);
      if (!info?.hasKnownSettings || cancelled) return;
      await storage.setItem(KEY_OEM_AUTOSTART_PROMPT_SHOWN, true);
      Alert.alert(
        'One more step for reliable tracking',
        `${info.manufacturer} phones have their own battery-saving settings that can stop location tracking even after you've allowed it. Please enable auto-start for this app to keep tracking reliable.`,
        [
          { text: 'Not now', style: 'cancel' },
          { text: 'Open settings', onPress: () => { LiveTracker.openAutostartSettings().catch(() => {}); } },
        ],
        { cancelable: true },
      );
    })();
    return () => {
      cancelled = true;
    };
  }, [hasBackgroundPermission, user]);
```

Import `storage` from `@/services/storage` if not already imported in this file (check first — `trackingLog.ts` already uses this same service, confirm the import path matches).

- [ ] **Step 6: Type-check**

Run: `cd react_native_basic_structure && npx tsc --noEmit`
Expected: no new errors.

- [ ] **Step 7: Build-verify the full app**

Run: `cd react_native_basic_structure/android && ./gradlew assembleRelease -I ../gradle-init/kapt-tmpdir.init.gradle`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
cd react_native_basic_structure
git add src/contexts/LocationContext.tsx
git commit -m "feat: one-time OEM autostart onboarding prompt"
```

(This repo — `react_native_basic_structure` — is a separate git repo from `expo-livetrack`; confirm with `git status` before committing here, since this plan's earlier commits are in the `expo-livetrack` repo.)

---

## Verification

1. Each task's own build+test steps above (Gradle unit tests, `assembleRelease`, `tsc --noEmit`).
2. Manual, on an actual affected device if at all possible (this environment has no physical Android device — only a stock emulator, which cannot reproduce OEM-specific autostart screens or long-duration Doze/still behavior):
   - Still-cadence fix: park the device, disable movement, confirm a location row (not just a HEARTBEAT event) appears at least every `stillIntervalMs` in the buffer/archive.
   - OEM autostart prompt: on a Xiaomi/Oppo/Vivo/Realme device, grant background location, confirm the prompt fires once and "Open settings" lands on that OEM's real autostart screen; confirm it never fires again on subsequent app opens.
   - Kill-survival end-to-end: per the design doc's own definition of done (`docs/2026-05-31-location-tracker-design.md:187-192`) — survive swipe-kill + reboot on 3+ OEM devices for an 8-hour ride with <2% point loss. This is beyond what can be verified in this environment; flag to the user as the real acceptance test before considering this fully "won't appear again."
3. After both fixes ship, re-run the same field-data cross-check methodology used to diagnose this (pull a future day's `location_log_*.ndjson` + `tracker_events`, re-run the gap/heartbeat classification) to confirm the gap rate actually drops — the true proof this worked.

## Risks / Notes

- OEM `ComponentName` values are undocumented and can change across ROM/MIUI/ColorOS versions; the ordered-candidate-list + generic-app-info-fallback design bounds the failure mode to "lands on the wrong screen," never a crash.
- The still-cadence fix's `LocationGate` was already reviewed as correct in design (per its own doc comments citing production evidence) but has never been through this session's actual task-review gate — Task 1 provides that first real review before it ships.
- This plan does not attempt the design doc's "WorkManager periodic check" (line 124, alongside the AlarmManager watchdog) — the existing 15-minute `Watchdog` + `START_STICKY` + `BootReceiver` combination is judged sufficient defense-in-depth for now; adding a second redundant periodic-restart mechanism was considered and deferred as separately schedulable hardening, not required to address the two diagnosed issues.
