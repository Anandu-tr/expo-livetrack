# Durable Background Upload + Diagnostics — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `expo-livetrack`'s background uploader refresh its auth token without the JS app being alive, report upload/SQL failures to Crashlytics via a vendor-agnostic seam, guarantee no buffered points are lost on app update, and ship a debug APK.

**Architecture:** The plugin stays auth- and vendor-agnostic. Token refresh is a generic, config-driven HTTP exchange performed natively in `UploadWorker`. Diagnostics go through an internal `DiagnosticsReporter` SPI whose only Firebase implementation is a `compileOnly` soft dependency selected by an init flag. No Room schema change, and the destructive-migration fallback is removed so a future schema bump can't wipe the buffer.

**Tech Stack:** Kotlin (Android, Room, WorkManager, HttpURLConnection), TypeScript (Expo module JS surface), Jest, Firebase Crashlytics (host-app-provided), Expo prebuild + Gradle.

## Global Constraints

- Plugin core MUST NOT hard-depend on Firebase. Firebase enters only as config data (token refresh) or via the `compileOnly` `CrashlyticsReporter` behind `DiagnosticsReporter`.
- NO Room schema change in this work. `BufferDb` stays `version = 1`; the `points` table is unchanged. (Data-safety requirement.)
- Token refresh config absent ⇒ behavior identical to today (static token). Backward compatible.
- All diagnostics/reporter calls are best-effort and exception-safe (`runCatching`/try-catch `Throwable`); they MUST never affect upload correctness or the buffer.
- Existing SharedPreferences file name: `livetrack_prefs` (constant `Prefs.NAME` / `LiveTrackModule.PREFS`). Existing keys live in `Prefs.kt`.
- Crashlytics SDK version pinned to align with the `@react-native-firebase` Firebase BoM in `example/` (target `com.google.firebase:firebase-crashlytics:19.2.1`; adjust to match the resolved BoM).
- Expiry skew for proactive refresh: `EXPIRY_SKEW_MS = 5 * 60 * 1000`.

---

## File Structure

**Plugin (TypeScript)**
- `src/LiveTrack.types.ts` — add `TokenRefresh`, `Diagnostics`; extend `StartConfig`.
- `src/LiveTrack.ts` — already spreads `...config`; covered by a forwarding test.

**Plugin (Kotlin)**
- `android/src/main/java/expo/modules/livetrack/Prefs.kt` — new keys.
- `android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt` — persist new config.
- `android/src/main/java/expo/modules/livetrack/diagnostics/DiagnosticsReporter.kt` — **new**: SPI + `Noop` + factory.
- `android/src/main/java/expo/modules/livetrack/diagnostics/CrashlyticsReporter.kt` — **new**: soft-dep impl.
- `android/src/main/java/expo/modules/livetrack/sync/TokenRefreshSupport.kt` — **new**: pure helpers (JSON path, expiry) — JVM-unit-testable.
- `android/src/main/java/expo/modules/livetrack/sync/UploadWorker.kt` — refresh + reporter wiring.
- `android/src/main/java/expo/modules/livetrack/TrackingService.kt` — capture-insert failure → reporter.
- `android/src/main/java/expo/modules/livetrack/buffer/BufferDb.kt` — remove destructive fallback.
- `android/build.gradle` — `compileOnly` Crashlytics.

**Tests**
- `src/__tests__/LiveTrack.test.ts` — JS forwarding.
- `android/src/test/java/expo/modules/livetrack/sync/TokenRefreshSupportTest.kt` — **new** JVM unit test.
- `android/src/androidTest/.../BufferDbMigrationTest.kt` — **new** instrumented data-safety test.

**Example app**
- `example/package.json`, `example/app.json`, `example/App.tsx`.

---

### Task 1: JS config surface for `tokenRefresh` + `diagnostics`

**Files:**
- Modify: `src/LiveTrack.types.ts`
- Test: `src/__tests__/LiveTrack.test.ts`

**Interfaces:**
- Produces: `StartConfig.tokenRefresh?: TokenRefresh`, `StartConfig.diagnostics?: Diagnostics`.
  - `TokenRefresh = { url: string; method?: 'POST'; headers?: Record<string,string>; body: string; tokenPath: string; expiresInPath?: string }`
  - `Diagnostics = { crashlytics?: boolean }`

- [ ] **Step 1: Write the failing test** — append to `src/__tests__/LiveTrack.test.ts`:

```ts
test('start forwards tokenRefresh and diagnostics to native', async () => {
  await LiveTracker.start({
    url: 'u',
    token: 'tok',
    userId: 'x',
    tokenRefresh: {
      url: 'https://refresh.example/v1/token?key=K',
      body: 'grant_type=refresh_token&refresh_token=RT',
      tokenPath: 'id_token',
      expiresInPath: 'expires_in',
    },
    diagnostics: { crashlytics: true },
  });
  expect(native.start).toHaveBeenCalledWith(
    expect.objectContaining({
      tokenRefresh: expect.objectContaining({
        url: 'https://refresh.example/v1/token?key=K',
        body: 'grant_type=refresh_token&refresh_token=RT',
        tokenPath: 'id_token',
        expiresInPath: 'expires_in',
      }),
      diagnostics: { crashlytics: true },
    }),
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- LiveTrack.test.ts`
Expected: FAIL — TypeScript error "Object literal may only specify known properties" (`tokenRefresh` not on `StartConfig`), or a type-check failure in the test.

- [ ] **Step 3: Add the types** — edit `src/LiveTrack.types.ts`, insert after the `Cadence` interface (line 16) and extend `StartConfig`:

```ts
export interface TokenRefresh {
  url: string;
  method?: 'POST';
  headers?: Record<string, string>;
  body: string;
  tokenPath: string;
  expiresInPath?: string;
}

export interface Diagnostics {
  crashlytics?: boolean;
}

export interface StartConfig {
  url: string;
  token: string;
  userId: string;
  cadence?: Cadence;
  tokenRefresh?: TokenRefresh;
  diagnostics?: Diagnostics;
}
```

(Replace the existing `StartConfig` block at lines 18-23 with the version above.)

- [ ] **Step 4: Run tests to verify pass**

Run: `npm test -- LiveTrack.test.ts`
Expected: PASS (all tests, including the two existing cadence tests).

Note: `LiveTracker.start` already does `LiveTrackModule.start({ ...config, cadence })`, so `tokenRefresh`/`diagnostics` forward automatically — no change to `LiveTrack.ts`.

- [ ] **Step 5: Commit**

```bash
git add src/LiveTrack.types.ts src/__tests__/LiveTrack.test.ts
git commit -m "feat(js): tokenRefresh + diagnostics config on StartConfig"
```

---

### Task 2: Native Prefs keys

**Files:**
- Modify: `android/src/main/java/expo/modules/livetrack/Prefs.kt`

**Interfaces:**
- Produces these `Prefs` constants (consumed by Tasks 3, 4, 5):
  `KEY_TOKEN_EXPIRES_AT`, `KEY_TOKEN_REFRESH_URL`, `KEY_TOKEN_REFRESH_METHOD`, `KEY_TOKEN_REFRESH_HEADERS`, `KEY_TOKEN_REFRESH_BODY`, `KEY_TOKEN_REFRESH_TOKEN_PATH`, `KEY_TOKEN_REFRESH_EXPIRES_PATH`, `KEY_DIAGNOSTICS_CRASHLYTICS`.

- [ ] **Step 1: Add the keys** — insert into `Prefs` object after `KEY_BATCH_SIZE` (line 21):

```kotlin
  // Token-refresh config (written by LiveTrackModule.start; consumed by UploadWorker).
  const val KEY_TOKEN_EXPIRES_AT = "tokenExpiresAt"
  const val KEY_TOKEN_REFRESH_URL = "tokenRefreshUrl"
  const val KEY_TOKEN_REFRESH_METHOD = "tokenRefreshMethod"
  const val KEY_TOKEN_REFRESH_HEADERS = "tokenRefreshHeaders" // JSON object string
  const val KEY_TOKEN_REFRESH_BODY = "tokenRefreshBody"
  const val KEY_TOKEN_REFRESH_TOKEN_PATH = "tokenRefreshTokenPath"
  const val KEY_TOKEN_REFRESH_EXPIRES_PATH = "tokenRefreshExpiresInPath"

  // Diagnostics config.
  const val KEY_DIAGNOSTICS_CRASHLYTICS = "diagnosticsCrashlytics"
```

- [ ] **Step 2: Compile check**

Run: `cd android && ./gradlew compileDebugKotlin` (or rely on Task 5's build; if Gradle is unavailable standalone, defer compile verification to Task 5).
Expected: compiles (constants only).

- [ ] **Step 3: Commit**

```bash
git add android/src/main/java/expo/modules/livetrack/Prefs.kt
git commit -m "feat(android): Prefs keys for token refresh + diagnostics"
```

---

### Task 3: Persist `tokenRefresh` + `diagnostics` in `start()`

**Files:**
- Modify: `android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt:71-97`

**Interfaces:**
- Consumes: `Prefs.KEY_*` from Task 2.
- Produces: prefs populated so `UploadWorker` (Task 5) and reporter factory (Task 4) can read them.

- [ ] **Step 1: Read the new config** — inside `AsyncFunction("start")`, after the `batchSize` line (line 82), add:

```kotlin
      @Suppress("UNCHECKED_CAST")
      val tokenRefresh = config["tokenRefresh"] as? Map<String, Any?>
      @Suppress("UNCHECKED_CAST")
      val diagnostics = config["diagnostics"] as? Map<String, Any?> ?: emptyMap()
      val crashlyticsOn = diagnostics["crashlytics"] as? Boolean ?: false
```

- [ ] **Step 2: Persist it** — inside the `edit().apply { ... }` block, before `putBoolean(Prefs.KEY_WAS_TRACKING, true)` (line 95), add:

```kotlin
        putBoolean(Prefs.KEY_DIAGNOSTICS_CRASHLYTICS, crashlyticsOn)
        if (tokenRefresh != null) {
          putString(Prefs.KEY_TOKEN_REFRESH_URL, tokenRefresh["url"] as? String)
          putString(Prefs.KEY_TOKEN_REFRESH_METHOD, tokenRefresh["method"] as? String ?: "POST")
          putString(Prefs.KEY_TOKEN_REFRESH_BODY, tokenRefresh["body"] as? String)
          putString(Prefs.KEY_TOKEN_REFRESH_TOKEN_PATH, tokenRefresh["tokenPath"] as? String)
          putString(Prefs.KEY_TOKEN_REFRESH_EXPIRES_PATH, tokenRefresh["expiresInPath"] as? String)
          val headers = (tokenRefresh["headers"] as? Map<*, *>)?.let { org.json.JSONObject(it).toString() }
          putString(Prefs.KEY_TOKEN_REFRESH_HEADERS, headers)
          // A fresh token was just passed in `token`; assume ~1h validity until the
          // first refresh recomputes it precisely.
          putLong(Prefs.KEY_TOKEN_EXPIRES_AT, System.currentTimeMillis() + 60 * 60 * 1000)
        } else {
          remove(Prefs.KEY_TOKEN_REFRESH_URL)
          putLong(Prefs.KEY_TOKEN_EXPIRES_AT, 0L)
        }
```

- [ ] **Step 3: Compile check** — defer to Task 5 build if Gradle standalone is unavailable; otherwise `./gradlew compileDebugKotlin` should pass.

- [ ] **Step 4: Commit**

```bash
git add android/src/main/java/expo/modules/livetrack/LiveTrackModule.kt
git commit -m "feat(android): persist tokenRefresh + diagnostics config in start()"
```

---

### Task 4: DiagnosticsReporter SPI + Crashlytics soft dependency

**Files:**
- Modify: `android/build.gradle:32-58` (dependencies block)
- Create: `android/src/main/java/expo/modules/livetrack/diagnostics/DiagnosticsReporter.kt`
- Create: `android/src/main/java/expo/modules/livetrack/diagnostics/CrashlyticsReporter.kt`

**Interfaces:**
- Produces:
  - `interface DiagnosticsReporter { fun recordFailure(kind: String, message: String, attrs: Map<String,String> = emptyMap()); fun log(message: String) }`
  - `DiagnosticsReporter.Noop` (object)
  - `object DiagnosticsReporters { fun resolve(prefs: SharedPreferences): DiagnosticsReporter }`
- Consumes: `Prefs.KEY_DIAGNOSTICS_CRASHLYTICS` (Task 2).

- [ ] **Step 1: Add the compileOnly dependency** — in `android/build.gradle` `dependencies { }`, after the coroutines line (line 52):

```gradle
  // Soft dependency: the plugin compiles against Crashlytics but never bundles it.
  // The host app supplies the SDK (via @react-native-firebase/crashlytics). If absent
  // at runtime, DiagnosticsReporters.resolve() degrades to Noop.
  compileOnly "com.google.firebase:firebase-crashlytics:19.2.1"
```

- [ ] **Step 2: Create the SPI** — `android/src/main/java/expo/modules/livetrack/diagnostics/DiagnosticsReporter.kt`:

```kotlin
package expo.modules.livetrack.diagnostics

import android.content.SharedPreferences
import expo.modules.livetrack.Prefs

/**
 * Vendor-agnostic sink for operational failures (upload/API + SQL). The plugin
 * core depends only on this interface; the Firebase implementation lives in
 * [CrashlyticsReporter] and is a compileOnly soft dependency.
 */
interface DiagnosticsReporter {
  fun recordFailure(kind: String, message: String, attrs: Map<String, String> = emptyMap())
  fun log(message: String)

  /** Does nothing. Default when diagnostics are off or the SDK is absent. */
  object Noop : DiagnosticsReporter {
    override fun recordFailure(kind: String, message: String, attrs: Map<String, String>) {}
    override fun log(message: String) {}
  }
}

/** Resolves the active reporter from persisted config. Never throws. */
object DiagnosticsReporters {
  fun resolve(prefs: SharedPreferences): DiagnosticsReporter {
    val useCrashlytics = prefs.getBoolean(Prefs.KEY_DIAGNOSTICS_CRASHLYTICS, false)
    if (!useCrashlytics) return DiagnosticsReporter.Noop
    // Instantiating CrashlyticsReporter links FirebaseCrashlytics; if the host app
    // does not ship the SDK this throws NoClassDefFoundError (a Throwable), which
    // runCatching catches -> safe degrade to Noop.
    return runCatching { CrashlyticsReporter() as DiagnosticsReporter }
      .getOrDefault(DiagnosticsReporter.Noop)
  }
}
```

- [ ] **Step 3: Create the Crashlytics impl** — `android/src/main/java/expo/modules/livetrack/diagnostics/CrashlyticsReporter.kt`:

```kotlin
package expo.modules.livetrack.diagnostics

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Reports failures to Firebase Crashlytics. Crashlytics auto-initializes from the
 * host app's google-services.json. Every call is exception-safe so a misconfigured
 * host never affects the capture/upload path.
 */
internal class CrashlyticsReporter : DiagnosticsReporter {
  private val crashlytics = FirebaseCrashlytics.getInstance()

  override fun recordFailure(kind: String, message: String, attrs: Map<String, String>) {
    runCatching {
      crashlytics.setCustomKey("livetrack_kind", kind)
      attrs.forEach { (k, v) -> crashlytics.setCustomKey("livetrack_$k", v) }
      crashlytics.log("livetrack:$kind $message")
      crashlytics.recordException(LiveTrackDiagnosticException(kind, message))
    }
  }

  override fun log(message: String) {
    runCatching { crashlytics.log(message) }
  }
}

/** Carrier exception so Crashlytics groups non-fatals by kind. */
internal class LiveTrackDiagnosticException(kind: String, message: String) :
  Exception("[$kind] $message")
```

- [ ] **Step 4: Compile check** — defer to Task 5 build. (`compileOnly` lets these classes compile; runtime linking is exercised in the APK.)

- [ ] **Step 5: Commit**

```bash
git add android/build.gradle android/src/main/java/expo/modules/livetrack/diagnostics/
git commit -m "feat(android): DiagnosticsReporter SPI + Crashlytics soft dependency"
```

---

### Task 5: UploadWorker — token refresh (proactive + reactive) + failure reporting

**Files:**
- Create: `android/src/main/java/expo/modules/livetrack/sync/TokenRefreshSupport.kt`
- Create: `android/src/test/java/expo/modules/livetrack/sync/TokenRefreshSupportTest.kt`
- Modify: `android/src/main/java/expo/modules/livetrack/sync/UploadWorker.kt`

**Interfaces:**
- Consumes: `Prefs.KEY_*` (Task 2), `DiagnosticsReporters.resolve` (Task 4).
- Produces (pure helpers, JVM-testable):
  - `object TokenRefreshSupport { fun jsonAtPath(root: org.json.JSONObject, path: String): String?; fun expiresAtFrom(nowMs: Long, expiresInSeconds: Long?): Long? }`

- [ ] **Step 1: Write the failing unit test** — `android/src/test/java/expo/modules/livetrack/sync/TokenRefreshSupportTest.kt`:

```kotlin
package expo.modules.livetrack.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenRefreshSupportTest {
  @Test fun readsTopLevelTokenAndExpiry() {
    val json = JSONObject("""{"id_token":"NEW","expires_in":"3600"}""")
    assertEquals("NEW", TokenRefreshSupport.jsonAtPath(json, "id_token"))
    assertEquals("3600", TokenRefreshSupport.jsonAtPath(json, "expires_in"))
  }

  @Test fun readsNestedPath() {
    val json = JSONObject("""{"data":{"token":"T"}}""")
    assertEquals("T", TokenRefreshSupport.jsonAtPath(json, "data.token"))
  }

  @Test fun missingPathIsNull() {
    val json = JSONObject("""{"a":1}""")
    assertNull(TokenRefreshSupport.jsonAtPath(json, "b"))
    assertNull(TokenRefreshSupport.jsonAtPath(json, "a.b"))
  }

  @Test fun expiresAtAddsSeconds() {
    assertEquals(1_000_000L + 3600_000L, TokenRefreshSupport.expiresAtFrom(1_000_000L, 3600L))
  }

  @Test fun expiresAtNullWhenNoTtl() {
    assertNull(TokenRefreshSupport.expiresAtFrom(1_000_000L, null))
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*TokenRefreshSupportTest"`
Expected: FAIL — `TokenRefreshSupport` unresolved.

- [ ] **Step 3: Create the helper** — `android/src/main/java/expo/modules/livetrack/sync/TokenRefreshSupport.kt`:

```kotlin
package expo.modules.livetrack.sync

import org.json.JSONObject

/** Pure helpers for the generic token-refresh exchange (no Android deps). */
object TokenRefreshSupport {
  /** Walk a dotted path over nested JSON objects; returns the leaf as String, or null. */
  fun jsonAtPath(root: JSONObject, path: String): String? {
    var cur: Any? = root
    for (seg in path.split(".")) {
      cur = (cur as? JSONObject)?.opt(seg) ?: return null
    }
    return cur?.toString()
  }

  /** Absolute expiry epoch-ms from a TTL in seconds, or null if no TTL given. */
  fun expiresAtFrom(nowMs: Long, expiresInSeconds: Long?): Long? =
    expiresInSeconds?.let { nowMs + it * 1000 }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*TokenRefreshSupportTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Wire refresh + reporting into UploadWorker** — replace the body of `doWork()` and add helpers. Specifically:

(5a) Add imports at the top of `UploadWorker.kt` (after existing imports):

```kotlin
import expo.modules.livetrack.Prefs
import expo.modules.livetrack.diagnostics.DiagnosticsReporter
import expo.modules.livetrack.diagnostics.DiagnosticsReporters
```

(5b) Replace `doWork()` (lines 49-97) with:

```kotlin
  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val url = prefs.getString("url", null)
    var token = prefs.getString("token", null)
    val batchSize = prefs.getInt("batchSize", 50)
    val reporter = DiagnosticsReporters.resolve(prefs)
    val refreshCfg = loadRefreshConfig(prefs)

    if (url.isNullOrBlank()) {
      return@withContext Result.success()
    }

    // Proactive refresh: if we know the token is near expiry, refresh before posting.
    if (refreshCfg != null) {
      val expAt = prefs.getLong(Prefs.KEY_TOKEN_EXPIRES_AT, 0L)
      val due = expAt in 1 until (System.currentTimeMillis() + EXPIRY_SKEW_MS)
      if (due) {
        val fresh = refreshToken(prefs, refreshCfg)
        if (fresh == null) {
          reporter.recordFailure("token-refresh", "proactive refresh failed",
            mapOf("host" to host(refreshCfg.url)))
        } else {
          token = fresh
        }
      }
    }

    if (token.isNullOrBlank()) {
      return@withContext Result.success()
    }

    val dao: PointDao = BufferDb.getInstance(applicationContext).pointDao()

    val rows = runCatching { dao.unsynced(batchSize) }.getOrElse {
      reporter.recordFailure("db-read", it.message ?: "unsynced failed",
        mapOf("batchSize" to batchSize.toString()))
      return@withContext Result.retry()
    }
    if (rows.isEmpty()) return@withContext Result.success()

    val body = buildBody(rows)

    var outcome = try {
      post(url, token, body)
    } catch (e: Exception) {
      reporter.recordFailure("upload-network", e.message ?: "network error",
        mapOf("bufferedCount" to (runCatching { dao.count() }.getOrDefault(-1)).toString()))
      emitSyncError(e.message ?: "network error", null, dao)
      return@withContext Result.retry()
    }

    // Reactive refresh: a single 401 -> refresh once -> retry the same batch once.
    if (outcome.code == 401 && refreshCfg != null) {
      val fresh = refreshToken(prefs, refreshCfg)
      if (fresh != null) {
        token = fresh
        outcome = try {
          post(url, token, body)
        } catch (e: Exception) {
          reporter.recordFailure("upload-network", e.message ?: "network error after refresh")
          emitSyncError(e.message ?: "network error", null, dao)
          return@withContext Result.retry()
        }
      } else {
        reporter.recordFailure("token-refresh", "reactive refresh failed",
          mapOf("host" to host(refreshCfg.url)))
      }
    }

    when {
      outcome.code in 200..299 -> {
        val acceptedIds = parseAcceptedIds(outcome.body)
        if (acceptedIds.isNotEmpty()) {
          runCatching { dao.deleteByIds(acceptedIds) }.onFailure {
            reporter.recordFailure("db-write", it.message ?: "deleteByIds failed",
              mapOf("idsCount" to acceptedIds.size.toString()))
          }
        }
        Result.success()
      }
      outcome.code == 401 || outcome.code == 403 || outcome.code in 400..499 -> {
        reporter.recordFailure("upload-http", "server rejected",
          mapOf("status" to outcome.code.toString(), "host" to host(url)))
        emitSyncError("server rejected (${outcome.code})", outcome.code, dao)
        Result.failure()
      }
      else -> {
        reporter.recordFailure("upload-http", "server error",
          mapOf("status" to outcome.code.toString(), "host" to host(url)))
        emitSyncError("server error (${outcome.code})", outcome.code, dao)
        Result.retry()
      }
    }
  }

  // --- Token refresh ------------------------------------------------------

  private data class RefreshConfig(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String,
    val tokenPath: String,
    val expiresInPath: String?,
  )

  private fun loadRefreshConfig(prefs: android.content.SharedPreferences): RefreshConfig? {
    val url = prefs.getString(Prefs.KEY_TOKEN_REFRESH_URL, null) ?: return null
    val body = prefs.getString(Prefs.KEY_TOKEN_REFRESH_BODY, null) ?: return null
    val tokenPath = prefs.getString(Prefs.KEY_TOKEN_REFRESH_TOKEN_PATH, null) ?: return null
    val method = prefs.getString(Prefs.KEY_TOKEN_REFRESH_METHOD, "POST") ?: "POST"
    val headers = runCatching {
      val raw = prefs.getString(Prefs.KEY_TOKEN_REFRESH_HEADERS, null) ?: "{}"
      val obj = JSONObject(raw)
      buildMap<String, String> { obj.keys().forEach { put(it, obj.getString(it)) } }
    }.getOrDefault(emptyMap())
    val expiresInPath = prefs.getString(Prefs.KEY_TOKEN_REFRESH_EXPIRES_PATH, null)
    return RefreshConfig(url, method, headers, body, tokenPath, expiresInPath)
  }

  /** Performs the generic refresh; persists new token + expiry. Returns token or null. */
  private fun refreshToken(prefs: android.content.SharedPreferences, cfg: RefreshConfig): String? {
    val conn = (URL(cfg.url).openConnection() as HttpURLConnection).apply {
      requestMethod = cfg.method
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      doOutput = true
      cfg.headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    return try {
      conn.outputStream.use { it.write(cfg.body.toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
      if (code !in 200..299) return null
      val json = JSONObject(text)
      val token = TokenRefreshSupport.jsonAtPath(json, cfg.tokenPath) ?: return null
      val expiresIn = cfg.expiresInPath?.let { TokenRefreshSupport.jsonAtPath(json, it)?.toLongOrNull() }
      val expiresAt = TokenRefreshSupport.expiresAtFrom(System.currentTimeMillis(), expiresIn)
      prefs.edit().apply {
        putString("token", token)
        if (expiresAt != null) putLong(Prefs.KEY_TOKEN_EXPIRES_AT, expiresAt)
        apply()
      }
      token
    } catch (e: Exception) {
      null
    } finally {
      conn.disconnect()
    }
  }

  private fun host(u: String): String = runCatching { URL(u).host }.getOrDefault("?")
```

(5c) Add `EXPIRY_SKEW_MS` to the `companion object` (next to `TIMEOUT_MS`, line 195):

```kotlin
    private const val EXPIRY_SKEW_MS = 5 * 60 * 1000L
```

- [ ] **Step 6: Build the module + run unit tests**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS (TokenRefreshSupportTest + any existing). Compilation of `UploadWorker.kt` succeeds.

- [ ] **Step 7: Commit**

```bash
git add android/src/main/java/expo/modules/livetrack/sync/ android/src/test/java/expo/modules/livetrack/sync/
git commit -m "feat(android): proactive+reactive token refresh and failure reporting in UploadWorker"
```

---

### Task 6: Capture-insert failure reporting (TrackingService)

**Files:**
- Modify: `android/src/main/java/expo/modules/livetrack/TrackingService.kt` (the location-insert site near line 277, and the event-insert site near line 511)

**Interfaces:**
- Consumes: `DiagnosticsReporters.resolve` (Task 4), `Prefs.NAME`.

- [ ] **Step 1: Resolve a reporter once** — find where `TrackingService` reads prefs/userId (it already reads SharedPreferences). Add a lazily-resolved reporter field near the top of the class (after `private var userId` line 66):

```kotlin
  private val reporter: expo.modules.livetrack.diagnostics.DiagnosticsReporter by lazy {
    expo.modules.livetrack.diagnostics.DiagnosticsReporters.resolve(
      getSharedPreferences(expo.modules.livetrack.Prefs.NAME, Context.MODE_PRIVATE)
    )
  }
```

- [ ] **Step 2: Wrap the location insert** — locate the `dao.insert(point)` call that persists the `PointEntity` built at line 277 (it follows the `val point = PointEntity(...)` block). Wrap it:

```kotlin
    runCatching { dao.insert(point) }.onFailure {
      reporter.recordFailure("db-write", it.message ?: "insert(location) failed",
        mapOf("hasLocation" to "true"))
    }
```

(If the existing call is already inside a coroutine/`runCatching`, add the `reporter.recordFailure(...)` inside its failure branch instead of double-wrapping.)

- [ ] **Step 3: Wrap the event insert** — do the same at the event `PointEntity` insert near line 511:

```kotlin
    runCatching { dao.insert(eventRow) }.onFailure {
      reporter.recordFailure("db-write", it.message ?: "insert(event) failed",
        mapOf("hasLocation" to "false"))
    }
```

(Use the actual local variable name at that site for the event entity; adapt `eventRow` to match.)

- [ ] **Step 4: Build**

Run: `cd android && ./gradlew compileDebugKotlin` (or defer to Task 9 APK build).
Expected: compiles.

- [ ] **Step 5: Commit**

```bash
git add android/src/main/java/expo/modules/livetrack/TrackingService.kt
git commit -m "feat(android): report SQL insert failures from capture path"
```

---

### Task 7: Remove destructive migration; data-safety test

**Files:**
- Modify: `android/src/main/java/expo/modules/livetrack/buffer/BufferDb.kt:29-38`
- Create: `android/src/androidTest/java/expo/modules/livetrack/buffer/BufferDbMigrationTest.kt`

**Interfaces:**
- Consumes: existing `BufferDb`, `PointEntity`, `PointDao`.

- [ ] **Step 1: Write the data-safety test** — `android/src/androidTest/java/expo/modules/livetrack/buffer/BufferDbMigrationTest.kt`:

```kotlin
package expo.modules.livetrack.buffer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class BufferDbMigrationTest {
  @Test fun reopeningPreservesRows() {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val name = "migration_test.db"
    ctx.deleteDatabase(name)

    // First open: seed two rows, then close.
    var db = Room.databaseBuilder(ctx, BufferDb::class.java, name).build()
    db.pointDao().insert(PointEntity(userId = "u1", t = 1, lat = 1.0, lng = 2.0))
    db.pointDao().insert(PointEntity(userId = "u1", t = 2, eventType = "HEARTBEAT"))
    assertEquals(2, db.pointDao().count())
    db.close()

    // Reopen with the same (current) configuration: rows must survive.
    db = Room.databaseBuilder(ctx, BufferDb::class.java, name).build()
    assertEquals(2, db.pointDao().count())
    db.close()
    ctx.deleteDatabase(name)
  }
}
```

- [ ] **Step 2: Run it to verify current behavior** — (this passes today because the version is unchanged; it is the regression guard for future schema bumps.)

Run: `cd android && ./gradlew connectedDebugAndroidTest --tests "*BufferDbMigrationTest"`
Expected: PASS (requires a connected device/emulator). If no emulator is available in CI, document this as a manual gate and run it on the build machine before shipping.

- [ ] **Step 3: Remove the destructive fallback** — edit `BufferDb.build()` (lines 29-38):

```kotlin
    private fun build(context: Context): BufferDb =
      Room.databaseBuilder(
        context.applicationContext,
        BufferDb::class.java,
        DB_NAME,
      )
        // NO destructive fallback: the buffer holds un-uploaded user data. A future
        // schema change MUST add an explicit Migration here. Until then there are no
        // migrations to register (version is unchanged), and existing data is preserved.
        .build()
```

- [ ] **Step 4: Re-run the test**

Run: `cd android && ./gradlew connectedDebugAndroidTest --tests "*BufferDbMigrationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/src/main/java/expo/modules/livetrack/buffer/BufferDb.kt android/src/androidTest/java/expo/modules/livetrack/buffer/BufferDbMigrationTest.kt
git commit -m "fix(android): drop destructive migration fallback; add buffer-preservation test"
```

---

### Task 8: Example app wiring (deps, Crashlytics, App.tsx)

**Files:**
- Modify: `example/package.json`
- Modify: `example/app.json`
- Modify: `example/App.tsx`

**Interfaces:**
- Consumes: the new `StartConfig.tokenRefresh` / `diagnostics` (Task 1); Firebase auth `user.refreshToken`.

- [ ] **Step 1: Add the Crashlytics dependency** — in `example/package.json` `dependencies`, add (match the existing RNFirebase version `^21.6.1`):

```json
    "@react-native-firebase/crashlytics": "^21.6.1",
```

Run: `cd example && npm install`

- [ ] **Step 2: Wire the Crashlytics Gradle plugin** — in `example/app.json`, under `expo.plugins`, add an `expo-build-properties` entry (merge if one exists):

```json
[
  "expo-build-properties",
  {
    "android": {
      "extraMavenRepos": [],
      "gradlePluginDependencies": ["com.google.firebase:firebase-crashlytics-gradle:3.0.2"],
      "gradlePluginPortalRepositories": []
    }
  }
]
```

Also add the Crashlytics plugin application. If `expo-build-properties` in the installed version does not support `gradlePluginDependencies`, fall back to a tiny config plugin or `app.config.js` `mods` that (a) adds `classpath 'com.google.firebase:firebase-crashlytics-gradle:3.0.2'` to the project `build.gradle` and (b) `apply plugin: 'com.google.firebase.crashlytics'` in `app/build.gradle`. **Verify the resolved Firebase BoM** (`./android/app` after prebuild) and align Task 4's `compileOnly` Crashlytics version to it.

- [ ] **Step 3: Pass the new config + breadcrumbs in `App.tsx`** — extend `startTracking` (lines 126-134):

```tsx
  const FIREBASE_WEB_API_KEY = '<from example/google-services.json: client.api_key.current_key>';

  const startTracking = async (current: FirebaseAuthTypes.User) => {
    const idToken = await current.getIdToken();
    // VERIFY: current.refreshToken is non-empty on Android in @react-native-firebase/auth.
    const refreshToken = current.refreshToken;
    await LiveTracker.start({
      url: TRACK_URL,
      token: idToken,
      userId: current.uid,
      cadence: TEST_CADENCE,
      tokenRefresh: {
        url: `https://securetoken.googleapis.com/v1/token?key=${FIREBASE_WEB_API_KEY}`,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `grant_type=refresh_token&refresh_token=${encodeURIComponent(refreshToken)}`,
        tokenPath: 'id_token',
        expiresInPath: 'expires_in',
      },
      diagnostics: { crashlytics: true },
    });
  };
```

- [ ] **Step 4: Add JS-side Crashlytics breadcrumbs** — at the top of `App.tsx` add `import crashlytics from '@react-native-firebase/crashlytics';` and in the existing `onSyncError` handler (line 211) add a breadcrumb before the refresh logic:

```tsx
      crashlytics().log(`syncError status=${err.status ?? '?'} buffered=${err.bufferedCount ?? '?'}`);
      if (err.status) crashlytics().recordError(new Error(`livetrack sync ${err.status}: ${err.message}`));
```

- [ ] **Step 5: Verify the refresh-token assumption** — before building, confirm `current.refreshToken` is populated:

Run a quick check in the running app (temporary `console.log(current.refreshToken)` in `startTracking`) or inspect `@react-native-firebase/auth` types in `example/node_modules`. If empty on Android: capture the refresh token from the sign-in `userCredential` at login and stash it, or implement the native TokenProvider fallback (spec §11 risk 1). Do not proceed to Task 9 until a non-empty refresh token reaches `tokenRefresh.body`.

- [ ] **Step 6: Commit**

```bash
git add example/package.json example/package-lock.json example/app.json example/App.tsx
git commit -m "feat(example): wire token refresh config + Crashlytics diagnostics"
```

---

### Task 9: Build the debug APK

**Files:** none (build artifact only)

- [ ] **Step 1: Prebuild native projects**

Run: `cd example && npx expo prebuild --platform android --clean`
Expected: `example/android/` generated with `google-services.json` picked up and the Crashlytics plugin applied.

- [ ] **Step 2: Assemble the debug APK**

Run: `cd example/android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `example/android/app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Sanity-check the artifact**

Run: `ls -la example/android/app/build/outputs/apk/debug/app-debug.apk`
Expected: file exists, non-trivial size.

- [ ] **Step 4: Manual verification checklist** (on a device, over an existing install with a non-empty buffer):
  - Install the APK over the previous build; confirm `bufferedCount` is unchanged immediately after update (data-safety).
  - With the app **backgrounded**, confirm the buffer drains (token refreshes natively) — watch Cloud Function logs for `acceptedIds` and advancing ids.
  - Force a failure (airplane mode mid-upload) and confirm a non-fatal appears in Crashlytics with `livetrack_kind`.
  - Leave app killed >1h; confirm uploads still succeed afterward.

- [ ] **Step 5: Hand off the artifact** — report the APK path to the user. (No commit; build output is gitignored.)

---

## Self-Review

**Spec coverage:**
- §5 token refresh → Tasks 1,2,3,5,8. ✓
- §6 diagnostics/Crashlytics → Tasks 1,3,4,5,6,8. ✓
- §6.3 instrumented failure sites: upload-http/network (T5), token-refresh (T5), db-write delete (T5), db-read (T5), capture insert db-write (T6). ✓
- §7 data-safe update (no schema change, remove destructive fallback, migration test, prefs/db persist) → Task 7 + Global Constraints. ✓
- §10 testing: JS forwarding (T1), token helpers JVM unit (T5), migration instrumented (T7), manual APK checklist (T9). ✓
- §11 risk 1 (refreshToken) → Task 8 Step 5 gate. risk 2 (Crashlytics version) → Task 4/Task 8 alignment notes. ✓
- §12 deliverables (plugin changes, example wiring, debug APK) → Tasks 1-9. ✓

**Placeholder scan:** No "TBD/TODO". The two `<...>` items (FIREBASE_WEB_API_KEY, gradle-plugin fallback) are explicit verify-and-fill steps with the source named, not vague placeholders.

**Type consistency:** `DiagnosticsReporter.recordFailure(kind, message, attrs)` and `DiagnosticsReporters.resolve(prefs)` used identically across Tasks 4/5/6. `TokenRefreshSupport.jsonAtPath`/`expiresAtFrom` defined in T5 and used only in T5. `Prefs.KEY_*` defined in T2, used in T3/T5. `StartConfig.tokenRefresh`/`diagnostics` defined T1, consumed T3 (native map) / T8 (JS). Consistent.
