# expo-livetrack: Durable Background Upload + Diagnostics — Design

Date: 2026-06-19
Status: Approved (design), pending spec review
Author: pairing session (Anandu + Claude)

## 1. Background & problem

`expo-livetrack` buffers GPS fixes + events on-device (Room, table `points`) and a
background `UploadWorker` (WorkManager) flushes them to the `ingest` Cloud Function,
which verifies a Firebase ID token and writes to RTDB under `tracks/{uid}`.

A production device (`r1Skp…`, "Jefrin") accumulated **29,046** un-uploaded points.
Root cause, confirmed from live logs:

- The upload Bearer token is a Firebase **ID token captured once at `start()`** and
  stored in `SharedPreferences`. Firebase ID tokens expire after ~1 hour.
- The only refresh path is JS-side: native emits `onSyncError(401)`, the JS app
  catches it and re-mints the token (`App.tsx`). **When the app is killed/backgrounded,
  JS is not alive**, so the token is never refreshed. Every background upload then
  returns `401 auth/id-token-expired`, nothing drains, and the buffer grows unbounded.

A server-side change has already been deployed (owner `u` now taken from the verified
token instead of the payload, removing a separate rejection path; logging trimmed to
`uid` + `received` + `acceptedIds` + `rejected{count,reason}`). That is live and
unrelated to the client work specified here.

This spec covers the **client** changes shipped in a new APK.

## 2. Goals

1. **Durable token refresh** — background uploads succeed even when the app is killed,
   without the plugin depending on any specific auth vendor.
2. **Diagnostics → crash reporting** — upload API failures and SQL read/write failures
   are reported to Crashlytics, including failures that happen while the app is killed,
   without the plugin hard-depending on Firebase.
3. **Data-safe update** — installing the new APK must not delete or drop any existing
   buffered points, and must not miss data.
4. Produce an installable **debug APK** of `example/`.

## 3. Non-goals

- No change to the wire contract / `ingest` function (already deployed separately).
- No Room schema change (deliberately — see §6.3).
- No iOS work in this spec (the stuck device is Android; iOS `Uploader.swift` mirrors
  the same token weakness and is tracked as follow-up, not built here).
- No increase to drain throughput (batch size / worker looping) — separate concern.

## 4. Design principle: the tracker is a reusable plugin

The module must stay **auth-agnostic and vendor-agnostic**. Firebase-specific details
enter only as (a) configuration data passed at `start()`, or (b) an isolated, swappable
reporter class behind a generic interface. Swapping Firebase Auth → another auth, or
Crashlytics → Sentry, must not churn the plugin core.

## 5. Component: durable token refresh (config-driven)

### 5.1 Public config (JS `start()` options)

New optional field on `LiveTrackConfig`:

```ts
tokenRefresh?: {
  url: string;            // token endpoint, e.g. Firebase securetoken
  method?: 'POST';        // default POST
  headers?: Record<string,string>;
  body: string;           // request body; may contain the stored refresh secret
  tokenPath: string;      // JSON path to the new token, e.g. "id_token"
  expiresInPath?: string; // JSON path to TTL seconds, e.g. "expires_in"
};
```

This is **pure data** — the plugin performs a generic OAuth2-style refresh. The example
app fills it with Firebase:

```ts
tokenRefresh: {
  url: `https://securetoken.googleapis.com/v1/token?key=${FIREBASE_WEB_API_KEY}`,
  body: `grant_type=refresh_token&refresh_token=${user.refreshToken}`,
  tokenPath: 'id_token',
  expiresInPath: 'expires_in',
}
```

> ⚠️ **Must verify during implementation:** that `@react-native-firebase/auth` exposes
> `user.refreshToken` (non-empty) on Android. If it does not, fall back to capturing the
> refresh token at sign-in via the auth result, or (last resort) the native
> TokenProvider SPI. This is the single biggest implementation risk.

### 5.2 Native behavior (`UploadWorker`, `Prefs`)

- `Prefs` gains: `tokenRefreshUrl`, `tokenRefreshMethod`, `tokenRefreshHeaders` (JSON),
  `tokenRefreshBody`, `tokenRefreshTokenPath`, `tokenRefreshExpiresInPath`, and
  `tokenExpiresAt` (epoch ms, derived).
- **Proactive refresh:** before posting a batch, if `tokenRefresh` is configured and
  `now >= tokenExpiresAt - SKEW` (SKEW ≈ 5 min), refresh first.
- **Reactive refresh:** if a POST returns `401`, refresh once and retry the same batch a
  single time. A second `401` → treat as before (emit failure, do not loop).
- **Refresh call:** generic HTTP request to `tokenRefreshUrl`; parse `tokenPath` for the
  new token and `expiresInPath` for TTL; persist new token + recomputed `tokenExpiresAt`.
  On refresh failure: emit a diagnostic + `Result.retry()` (transient) — never drop rows.
- If `tokenRefresh` is **absent**, behavior is exactly today's (static token). Backward
  compatible.

### 5.3 Why this is decoupled

The plugin only knows "make this HTTP call, read the token at this JSON path." It never
imports Firebase. Any token system with a refresh endpoint works by changing config.

## 6. Component: diagnostics → Crashlytics (flag-driven, soft dependency)

### 6.1 Public config

```ts
diagnostics?: { crashlytics?: boolean };  // default false
```

Crashlytics auto-initializes from the host app's `google-services.json` via the Firebase
SDK already in the app — so **no Firebase config values are passed to the plugin**; only
the on/off flag.

### 6.2 Internal SPI

```kotlin
interface DiagnosticsReporter {
  fun recordFailure(kind: String, message: String, attrs: Map<String,String>)
  fun log(message: String)
}
```

- `NoopReporter` — default; does nothing.
- `CrashlyticsReporter` — selected when `diagnostics.crashlytics == true`. Calls
  `FirebaseCrashlytics.getInstance().recordException(...)` / `.setCustomKeys(...)` /
  `.log(...)`. Firebase Crashlytics is a **`compileOnly`** dependency of the module
  (plugin compiles against the API but does not bundle/force it); every call is wrapped
  so a missing SDK at runtime degrades to no-op instead of crashing
  (`try/catch (Throwable)` + a one-time availability check).

The reporter is resolved once (process singleton) from `Prefs` so the background worker
and capture service both use it without JS.

### 6.3 Instrumented failure sites

| Site | Kind | Attributes |
|---|---|---|
| `UploadWorker` HTTP non-2xx | `upload-http` | status, endpoint host, bufferedCount, batchSize |
| `UploadWorker` network/transport throw | `upload-network` | message, bufferedCount |
| `UploadWorker` token refresh failure | `token-refresh` | status, endpoint host |
| `UploadWorker` `deleteByIds` failure | `db-write` | idsCount |
| `PointDao.unsynced` read failure | `db-read` | batchSize |
| Capture insert failure (service) | `db-write` | hasLocation |

Because these run **natively** in the worker / capture service, they are captured even
when the app's JS is dead. The JS app additionally adds Crashlytics breadcrumbs for
auth/UI events (sign-in, start/stop, sync-error banner) for context.

### 6.4 App / build wiring

- `example/package.json`: add `@react-native-firebase/crashlytics`.
- `example/app.json`: add the Crashlytics Gradle plugin via `expo-build-properties`
  (`android.extraGradlePlugins` / equivalent) and ensure the Firebase Crashlytics SDK is
  available at the version the module's `compileOnly` expects (align with the RNFirebase
  BoM). Enable native crash collection.
- `App.tsx`: pass `diagnostics: { crashlytics: true }` and the `tokenRefresh` config to
  `LiveTracker.start()`.

## 7. Component: data-safe update

The hard requirement: a user on the new APK keeps every buffered point.

- **No Room schema change in this work.** DB `version` stays `1`; the `points` table is
  byte-identical, so Room opens the existing `livetrack_buffer.db` untouched on update.
  Verified that token state (Prefs) and diagnostics (Crashlytics) touch neither the
  schema nor the table.
- **Remove `fallbackToDestructiveMigration()`** from `BufferDb` regardless, replacing it
  with explicit migration handling, so a *future* schema bump can never silently wipe the
  buffer. With no schema change now this is a safety net (no migration to write yet); it
  prevents the landmine from firing later.
- `SharedPreferences` and the Room DB file both survive an app update (same `applicationId`,
  same internal storage) — no code clears them on start. Confirmed.
- **Migration test:** an instrumented/Robolectric test seeds rows in a v1 DB, reopens via
  `BufferDb` after the builder change, and asserts row count + contents are preserved.

## 8. Data flow (happy path, app killed)

```
capture (bg service) --insert--> Room(points)        [db-write failures -> reporter]
WorkManager fires UploadWorker (no JS):
  read unsynced(50) ----------------------------------[db-read failure -> reporter+retry]
  token near expiry? -> POST tokenRefresh.url -> new token in Prefs
  POST ingest (Bearer token)
    200 -> deleteByIds(acceptedIds)                   [db-write failure -> reporter]
    401 -> refresh once -> retry once
    non-2xx/network -> reporter + Result.retry()
```

## 9. Error handling summary

- Token refresh failure: report + `Result.retry()` (WorkManager backoff). Never drops rows.
- Persistent `401` after one refresh: report + stop (as today) so we don't hot-loop.
- All reporter calls are best-effort and exception-safe; diagnostics never affect upload
  correctness or the buffer.

## 10. Testing

- **JS:** `LiveTrack.test.ts` — `start()` forwards `tokenRefresh` + `diagnostics` to the
  native module.
- **Native (where harness allows):**
  - Token: proactive refresh triggers when `tokenExpiresAt` is near; reactive refresh on
    `401` then retry; no infinite loop on repeated `401`; absent config = legacy path.
  - Reporter: `NoopReporter` when flag off; `CrashlyticsReporter` selected when on;
    missing-SDK path degrades to no-op (no crash).
  - Migration safety: seed v1 → reopen → assert no data loss.
- **Manual:** build debug APK, install over an existing build with a non-empty buffer,
  confirm `bufferedCount` is preserved and that with the app **backgrounded** the buffer
  still drains (token refreshes natively); kill app for >1h and confirm drain resumes.

## 11. Risks & open items

1. **`user.refreshToken` exposure in RNFirebase** (§5.1) — primary risk; verify first.
2. **Crashlytics SDK version alignment** with the module's `compileOnly` (§6.2) — pin to
   the RNFirebase BoM; mismatch shows as runtime no-op (safe) but loses reporting.
3. **Refresh-token storage** lives in `SharedPreferences` (plaintext) — acceptable for
   this example app; note `EncryptedSharedPreferences` as a future hardening.
4. Expo prebuild config-plugin wiring for the Crashlytics Gradle plugin may need
   iteration against `expo-build-properties`.

## 12. Deliverables

- Plugin changes (token refresh + diagnostics SPI + BufferDb safety) with tests.
- Example app wiring (deps, `app.json`, `App.tsx`).
- A built **debug APK** of `example/`, artifact path handed back.
- This spec + an implementation plan (via writing-plans).
