# Fix ensureNotKilled() Battery-Optimization Dialog Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `LiveTracker.ensureNotKilled()` from re-launching Android's "ignore battery optimization" OS dialog on every single background→active transition, which produces an unbreakable prompt loop on some devices.

**Architecture:** Add a persisted (SharedPreferences) cooldown + attempt cap inside `ensureNotKilled()` itself, at the native Android layer — not the JS call site — so every current and future caller is protected, and the throttle survives a force-stop/relaunch. Once the cap is hit, `ensureNotKilled()` silently stops asking; the app's existing, separately-throttled in-app "Background Usage Restricted" banner (`LocationContext.tsx:441-469`, already gated to once per 10 minutes) remains as the fallback UI, unchanged.

**Tech Stack:** Kotlin, Android SharedPreferences, Expo Modules API (`AsyncFunction`/`Promise`, this module's existing `runCatching {}.onFailure {}` convention).

**Spec:** None on disk — captured inline below. Root cause was diagnosed by frame-by-frame review of a user-submitted screen recording (`WhatsApp Video 2026-09-15 at 12.21.17 PM.mp4`) plus reading `LocationContext.tsx` and `LiveTrackModule.kt`; the fix approach ("cooldown + cap, then fall back to the existing banner") was confirmed with the user over two lighter alternatives (cooldown-only-forever, and throttle-once-per-launch).

## Global Constraints

- iOS is out of scope: `LiveTrackModule.swift`'s `ensureNotKilled` is already a no-op (`ios/LiveTrackModule.swift:264-267`, "no battery-optimization concept") — this bug and its fix are Android-only.
- No JS-side changes required: `react_native_basic_structure/src/contexts/LocationContext.tsx:282-284` can keep calling `LiveTracker.ensureNotKilled()` unconditionally on every foreground transition — the new throttle lives entirely native-side, protecting that call site with zero diff there.
- Reuse the existing `Prefs` object (`android/src/main/java/expo/modules/livetrack/Prefs.kt`) for new SharedPreferences keys — this module has one designated source of truth for pref keys; do not inline raw string keys in `LiveTrackModule.kt`.
- Cooldown window: 10 minutes (`10 * 60 * 1000L`), matching the app's own existing "Background Usage Restricted" banner cadence (`LocationContext.tsx:452`) for a consistent UX cadence across both nag paths.
- Attempt cap: 3 consecutive un-confirmed attempts, matching this codebase's existing eviction-after-N-tries convention for the same "don't retry forever against an unconfirming signal" problem shape (`Cadence.maxUploadAttempts`, default 15, in the upload buffer — 3 is deliberately much lower here since this is an intrusive user-facing OS dialog, not a silent background retry).

---

## Context

A user reported (WhatsApp screen recording, 2026-09-15) a loop on their device: Android's native "Let app always run in background?" dialog fires, they tap "Set now," land on an OEM battery-management screen with the toggle already showing **on**, back out to the app, and the identical dialog fires again immediately — repeating for the full 18-second recording with no resolution. It reproduces on their device only; the person who filed it says it works fine on their own device.

Reading the code confirmed the exact mechanism:
- `LocationContext.tsx:276-284`'s `AppState` listener calls `LiveTracker.ensureNotKilled()` on **every** background→active transition, with no throttle.
- `LiveTrackModule.kt`'s `ensureNotKilled()` (`373-406`) checks `isBatteryOptIgnored(ctx)` (a thin wrapper over `PowerManager.isIgnoringBatteryOptimizations()`) and, if false, unconditionally re-launches `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — also with no throttle.
- Navigating into the OS settings screen and back **is itself** a background→active transition, so returning from the dialog's own settings screen immediately re-triggers the check.
- On stock/Pixel/Samsung Android, `isIgnoringBatteryOptimizations()` reliably flips true the moment the user grants it, so the loop self-resolves in one round-trip — consistent with it working fine on other devices. On some OEM skins (the reported screen's dark styling + "Open / Uninstall / Force stop" + single toggle strongly resembles a MIUI-family battery manager), that OS API is known not to reliably/immediately reflect a just-granted exemption, so the check keeps failing and the dialog keeps re-firing — fast and indefinitely, since nothing anywhere in the call path ever stops it.

This plan makes `ensureNotKilled()` throttle and eventually give up, so the behavior degrades gracefully (ask a few times, then stop and rely on the existing in-app banner) instead of looping forever, regardless of whether the underlying OEM detection issue can ever be fixed from the app side (it can't — it's outside this app's control).

## Files to Change

| File | Change |
|---|---|
| `android/src/main/java/expo/modules/livetrack/Prefs.kt` | Add two new SharedPreferences keys for the throttle/cap state |
| `android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt` | Rewrite `ensureNotKilled()` to check + persist cooldown and attempt-count before launching the OS intent; add the two new companion constants |

No changes to `src/*` (the TS wrapper), `react_native_basic_structure/src/*`, or iOS — this is a self-contained native Android fix inside one existing method.

---

### Task 1: Throttle and cap `ensureNotKilled()`'s OS dialog

**Files:**
- Modify: `android/src/main/java/expo/modules/livetrack/Prefs.kt:56-57`
- Modify: `android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt:373-406` (the `ensureNotKilled` `AsyncFunction` body)
- Modify: `android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt:589-592` (companion object constants)

**Interfaces:**
- Consumes: `Prefs` object's existing pattern (`object Prefs { const val KEY_... }`), `PREFS` companion constant already in `LiveTrackModule.kt:590`, `isBatteryOptIgnored(ctx: Context): Boolean` already defined at `LiveTrackModule.kt:507-513`.
- Produces: no new public/JS-visible surface — every `AsyncFunction`'s signature is unchanged; this is purely internal behavior of `ensureNotKilled()`, which already returns `Promise<void>` on the JS side (`src/LiveTrack.ts:66-68`) and needs no change there.

- [ ] **Step 1: Add the two new pref keys**

Edit `Prefs.kt`, inserting after the existing `KEY_LAST_BATTERY_OPT_IGNORED` line (56) and before the closing `}` (57):

```kotlin
  /**
   * ensureNotKilled() throttle state: last time the raw OS "ignore battery
   * optimization" dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) was
   * launched, and how many consecutive attempts have gone by without the OS
   * confirming the exemption. See LiveTrackModule.ensureNotKilled.
   */
  const val KEY_ENSURE_NOT_KILLED_LAST_ATTEMPT_AT = "ensureNotKilledLastAttemptAt"
  const val KEY_ENSURE_NOT_KILLED_ATTEMPT_COUNT = "ensureNotKilledAttemptCount"
```

- [ ] **Step 2: Run a quick sanity compile (this file alone)**

Run: `./gradlew :expo-livetrack:compileDebugKotlin` from `android/` (or `./gradlew assembleDebug` if the module isn't independently addressable — check the error message names the right task).
Expected: succeeds with the two new unused constants (Kotlin does not warn/fail on unused `const val`).

- [ ] **Step 3: Add the cooldown/cap constants**

Edit `LiveTrackModule.kt`'s companion object (currently lines 589-592):

```kotlin
  companion object {
    private const val PREFS = "livetrack_prefs"
    private const val REQ_ENABLE_LOCATION = 3003
    /** ensureNotKilled() throttle: matches the app's own in-app banner cadence. */
    private const val ENSURE_NOT_KILLED_COOLDOWN_MS = 10 * 60 * 1000L
    /** ensureNotKilled() cap: consecutive un-confirmed attempts before giving up. */
    private const val ENSURE_NOT_KILLED_MAX_ATTEMPTS = 3
  }
```

- [ ] **Step 4: Rewrite `ensureNotKilled()` with the cooldown + cap checks**

Replace the current body (`LiveTrackModule.kt:373-406`):

```kotlin
    // ensureNotKilled(): ask the OS to exempt us from battery optimization so the
    // foreground service isn't killed in Doze/standby. The package shows NO popup
    // of its own — this only fires the OS dialog when the host app calls it. OEM
    // autostart deep-links are handled on the JS side via expo-intent-launcher.
    AsyncFunction("ensureNotKilled") { promise: Promise ->
      runCatching {
        val ctx = context
        if (isBatteryOptIgnored(ctx)) {
          // Already exempt — nothing to ask.
          promise.resolve(null)
          return@runCatching
        }
        // VERIFY: appContext.currentActivity nullable getter on this expo-modules-core.
        val activity = appContext.currentActivity
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS must target a package and
        // requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (declared
        // in this module's AndroidManifest.xml; Play policy restricts its use to
        // apps with a qualifying background need — location tracking qualifies).
        // VERIFY: launching this from a non-Activity context needs FLAG_ACTIVITY_NEW_TASK.
        val intent = Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:${ctx.packageName}"),
        )
        if (activity != null) {
          activity.startActivity(intent)
        } else {
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          ctx.startActivity(intent)
        }
        // We can't await the dialog result here (no result contract); resolve once
        // launched. The host re-reads getState().batteryOptIgnored afterwards.
        promise.resolve(null)
      }.onFailure { promise.reject("ERR_ENSURE_NOT_KILLED", it.message, it) }
    }
```

with:

```kotlin
    // ensureNotKilled(): ask the OS to exempt us from battery optimization so the
    // foreground service isn't killed in Doze/standby. The package shows NO popup
    // of its own — this only fires the OS dialog when the host app calls it. OEM
    // autostart deep-links are handled on the JS side via expo-intent-launcher.
    //
    // Throttled and capped (state persisted in Prefs, so it survives force-stop /
    // relaunch): at most one OS dialog per ENSURE_NOT_KILLED_COOLDOWN_MS, and at
    // most ENSURE_NOT_KILLED_MAX_ATTEMPTS consecutive attempts before giving up
    // silently. Needed because on some OEMs (e.g. MIUI-style battery managers)
    // isBatteryOptIgnored() doesn't reliably reflect a just-granted exemption, so
    // an unthrottled caller re-launches this dialog on every foreground transition
    // — including the one caused by returning FROM the dialog's own settings
    // screen — producing an unbreakable prompt loop. The host app's own
    // "Background Usage Restricted" banner (LocationContext.tsx, throttled
    // separately) is the fallback once we give up here.
    AsyncFunction("ensureNotKilled") { promise: Promise ->
      runCatching {
        val ctx = context
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isBatteryOptIgnored(ctx)) {
          // Already exempt — nothing to ask. Reset so a later re-optimization
          // (user re-enables it manually, later in Android Settings) gets a
          // fresh set of attempts rather than staying capped forever.
          prefs.edit().putInt(Prefs.KEY_ENSURE_NOT_KILLED_ATTEMPT_COUNT, 0).apply()
          promise.resolve(null)
          return@runCatching
        }
        val attempts = prefs.getInt(Prefs.KEY_ENSURE_NOT_KILLED_ATTEMPT_COUNT, 0)
        if (attempts >= ENSURE_NOT_KILLED_MAX_ATTEMPTS) {
          // Given up for this exemption cycle — avoids an unbreakable loop on
          // devices where the OS never reflects the grant. The in-app banner is
          // the remaining fallback; nothing else to do here.
          promise.resolve(null)
          return@runCatching
        }
        val lastAttemptAt = prefs.getLong(Prefs.KEY_ENSURE_NOT_KILLED_LAST_ATTEMPT_AT, 0L)
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < ENSURE_NOT_KILLED_COOLDOWN_MS) {
          // Too soon since the last prompt — most commonly the foreground
          // transition caused by returning from the settings screen itself.
          promise.resolve(null)
          return@runCatching
        }
        // VERIFY: appContext.currentActivity nullable getter on this expo-modules-core.
        val activity = appContext.currentActivity
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS must target a package and
        // requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (declared
        // in this module's AndroidManifest.xml; Play policy restricts its use to
        // apps with a qualifying background need — location tracking qualifies).
        // VERIFY: launching this from a non-Activity context needs FLAG_ACTIVITY_NEW_TASK.
        val intent = Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:${ctx.packageName}"),
        )
        if (activity != null) {
          activity.startActivity(intent)
        } else {
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          ctx.startActivity(intent)
        }
        prefs.edit()
          .putLong(Prefs.KEY_ENSURE_NOT_KILLED_LAST_ATTEMPT_AT, now)
          .putInt(Prefs.KEY_ENSURE_NOT_KILLED_ATTEMPT_COUNT, attempts + 1)
          .apply()
        // We can't await the dialog result here (no result contract); resolve once
        // launched. The host re-reads getState().batteryOptIgnored afterwards.
        promise.resolve(null)
      }.onFailure { promise.reject("ERR_ENSURE_NOT_KILLED", it.message, it) }
    }
```

- [ ] **Step 5: Build-verify (full release build)**

Run from `react_native_basic_structure/android`:

```bash
./gradlew assembleRelease -I ../gradle-init/kapt-tmpdir.init.gradle
```

Expected: `BUILD SUCCESSFUL`. (The `-I` flag is required — without it, `kaptReleaseKotlin` fails on this machine with a `sqlite-jdbc` temp-dir `AccessDeniedException`.)

- [ ] **Step 6: Manual verification (no automated test harness exists for this OS-interaction code; this is the established verification method for this module)**

On a physical device (ideally the reporting user's, or another device on the same OEM skin if available):

1. Revoke the battery-optimization exemption for the app (Settings → Apps → [app] → Battery, or uninstall/reinstall).
2. Sign in and grant permissions so tracking starts.
3. Background and re-foreground the app several times within under 10 minutes (including navigating into the OS dialog's settings screen and back, mimicking the video). Confirm the OS dialog now fires **at most once** in that window, not on every single transition.
4. Keep repeating background/foreground transitions past the 10-minute cooldown, up to 3 total dialog launches. Confirm that after the 3rd un-confirmed attempt, no further OS dialogs appear on subsequent transitions — only the existing in-app "Background Usage Restricted" banner may still appear (unaffected by this change, still on its own 10-minute throttle).
5. Grant the exemption for real (via the OS dialog or Settings). Confirm `ensureNotKilled()` no longer prompts at all afterward.
6. From Android Settings, manually re-enable battery optimization for the app, then repeat step 3. Confirm the dialog fires again (verifies the attempt counter reset on success in Step 4 above, rather than staying permanently capped from an earlier cycle).
7. Mid-loop (before granting), force-stop the app from the OEM battery-management screen and relaunch. Confirm the cooldown/cap state persisted (SharedPreferences survives force-stop) — i.e. it does **not** reset to a fresh 3 attempts just from relaunching.

- [ ] **Step 7: Commit**

```bash
git add android/src/main/java/expo/modules/livetrack/Prefs.kt android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt
git commit -m "fix: throttle and cap ensureNotKilled() battery-optimization dialog"
```

---

## Self-Review

**Spec coverage:** The one requirement from the approved approach — "cooldown + cap, then fall back to the existing banner" — is fully implemented in Task 1: cooldown (Step 4's `lastAttemptAt` check), cap (Step 4's `attempts >= ENSURE_NOT_KILLED_MAX_ATTEMPTS` check), fallback (no code change needed — the existing banner in `LocationContext.tsx:441-469` already runs independently and is untouched). No gaps.

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N" placeholders — every step has literal, complete code.

**Type consistency:** `Prefs.KEY_ENSURE_NOT_KILLED_LAST_ATTEMPT_AT` / `KEY_ENSURE_NOT_KILLED_ATTEMPT_COUNT` are named identically in both the `Prefs.kt` addition (Step 1) and their three usages in `LiveTrackModule.kt` (Step 4). `ENSURE_NOT_KILLED_COOLDOWN_MS` / `ENSURE_NOT_KILLED_MAX_ATTEMPTS` are defined once (Step 3) and referenced identically in Step 4. No signature changes anywhere else in the file, so no cross-task drift is possible — this is a single-task plan.

## Risks / Notes

- This does not fix the underlying OEM `isIgnoringBatteryOptimizations()` unreliability — that's outside the app's control. It bounds the damage: a few tries, then quiet, instead of an infinite loop.
- If the reporting user's device never lets the exemption "stick" at all, they will still see tracking periodically affected by Doze/standby after the cap is reached — same as before this fix, but without the intrusive loop. Worth mentioning to them if they follow up.
- 10 minutes / 3 attempts are reasonable defaults matching existing in-repo conventions, not hard requirements — cheap to tune later if real-world use shows they should differ.
