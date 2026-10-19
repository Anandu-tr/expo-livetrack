package expo.modules.livetrack

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.livetrack.archive.CoverageCalculator
import expo.modules.livetrack.archive.ReplayRunner
import expo.modules.livetrack.buffer.ArchivedPointEntity
import expo.modules.livetrack.buffer.BufferDb
import expo.modules.livetrack.buffer.PointEntity
import expo.modules.livetrack.keepalive.KillStats
import expo.modules.livetrack.keepalive.Watchdog
import expo.modules.livetrack.oem.OemAutostart
import expo.modules.livetrack.sync.TokenProviders
import expo.modules.livetrack.sync.UploadWorker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Expo module bridge for LiveTrack.
 *
 * Owns the JS-facing control surface (start/stop/getState/requestPermissions/...)
 * and relays best-effort capture/diagnostic events from [TrackingService] to JS
 * via [LiveTrackEventBus] (same-process singleton).
 *
 * Module name "LiveTrack" must match `requireNativeModule('LiveTrack')` in JS.
 */
class LiveTrackModule : Module() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("LiveTrack")

    Events("onLocation", "onEvent", "onSyncError")

    OnCreate {
      // Relay events from the service to JS while this module (JS) is alive.
      LiveTrackEventBus.setListener { eventName, payload ->
        // VERIFY: sendEvent(name, Bundle) — confirmed present on Module base class.
        runCatching { sendEvent(eventName, payload) }
      }
    }

    OnDestroy {
      LiveTrackEventBus.setListener(null)
      scope.cancel()
    }

    // start(config): persist config + launch the foreground service.
    // AsyncFunction because the TS surface declares `start(): Promise<void>` —
    // a sync Function here returns `undefined` to JS and `.catch` chains crash.
    AsyncFunction("start") { config: Map<String, Any?>, promise: Promise ->
      val url = config["url"] as? String ?: ""
      val token = config["token"] as? String ?: ""
      val userId = config["userId"] as? String ?: ""

      @Suppress("UNCHECKED_CAST")
      val cadence = config["cadence"] as? Map<String, Any?> ?: emptyMap()
      val movingIntervalMs = numLong(cadence["movingIntervalMs"], 12_000L)
      val movingDistanceM = numDouble(cadence["movingDistanceM"], 30.0).toFloat()
      val stillIntervalMs = numLong(cadence["stillIntervalMs"], 120_000L)
      val maxAccuracyM = numDouble(cadence["maxAccuracyM"], 50.0)
      // Clamped to the uploader's SQL-variable budget: `deleteByIds`/`incrementAttempts`
      // bind one variable per id and SQLITE_MAX_VARIABLE_NUMBER is 999 on older SQLite.
      val batchSize = numLong(cadence["batchSize"], 50L).toInt().coerceIn(1, 500)
      val maxUploadAttempts = numLong(
        cadence["maxUploadAttempts"], UploadWorker.DEFAULT_MAX_UPLOAD_ATTEMPTS.toLong(),
      ).toInt().coerceAtLeast(1)

      // 0 = archive off. Clamped: the archive is a rolling window, not a data lake.
      val archiveDays = numLong(config["archiveDays"], 0L).toInt().coerceIn(0, 365)

      val tokenProviderClass = config["tokenProviderClass"] as? String
      @Suppress("UNCHECKED_CAST")
      val diagnostics = config["diagnostics"] as? Map<String, Any?> ?: emptyMap()
      val crashlyticsOn = diagnostics["crashlytics"] as? Boolean ?: false
      val lifecycleOn = diagnostics["lifecycleEvents"] as? Boolean ?: false

      // Persist for the uploader + reboot re-arm. wasTracking lets BootReceiver
      // / Watchdog know whether to resume after a reboot or process death.
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
        putString(Prefs.KEY_URL, url)
        putString(Prefs.KEY_TOKEN, token)
        putString(Prefs.KEY_USER_ID, userId)
        putLong(Prefs.KEY_MOVING_INTERVAL_MS, movingIntervalMs)
        putFloat(Prefs.KEY_MOVING_DISTANCE_M, movingDistanceM)
        putLong(Prefs.KEY_STILL_INTERVAL_MS, stillIntervalMs)
        putFloat(Prefs.KEY_MAX_ACCURACY_M, maxAccuracyM.toFloat())
        putInt(Prefs.KEY_BATCH_SIZE, batchSize)
        putInt(Prefs.KEY_MAX_UPLOAD_ATTEMPTS, maxUploadAttempts)
        putInt(Prefs.KEY_ARCHIVE_DAYS, archiveDays)
        putBoolean(Prefs.KEY_DIAGNOSTICS_CRASHLYTICS, crashlyticsOn)
        putBoolean(Prefs.KEY_DIAGNOSTICS_LIFECYCLE, lifecycleOn)
        putString(Prefs.KEY_TOKEN_PROVIDER_CLASS, tokenProviderClass)
        putBoolean(Prefs.KEY_WAS_TRACKING, true)
        apply()
      }

      val intent = Intent(context, TrackingService::class.java).apply {
        putExtra(TrackingService.EXTRA_START_REASON, "manual")
        putExtra(TrackingService.EXTRA_URL, url)
        putExtra(TrackingService.EXTRA_TOKEN, token)
        putExtra(TrackingService.EXTRA_USER_ID, userId)
        putExtra(TrackingService.EXTRA_MOVING_INTERVAL_MS, movingIntervalMs)
        putExtra(TrackingService.EXTRA_MOVING_DISTANCE_M, movingDistanceM)
        putExtra(TrackingService.EXTRA_STILL_INTERVAL_MS, stillIntervalMs)
        putExtra(TrackingService.EXTRA_MAX_ACCURACY_M, maxAccuracyM)
      }
      ContextCompat.startForegroundService(context, intent)

      // Keep-alive + safety-net flush; both unique+KEEP so this is idempotent.
      runCatching { Watchdog.schedule(context) }
      runCatching { UploadWorker.enqueuePeriodic(context) }

      // Record a permission-delta event (PERMISSION_GRANTED/REVOKED) vs last seen.
      scope.launch { runCatching { checkPermissionDelta(context) } }

      promise.resolve(null)
    }

    // stop(): stop the foreground service and tear down keep-alive.
    AsyncFunction("stop") { promise: Promise ->
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(Prefs.KEY_WAS_TRACKING, false).apply()
      runCatching { Watchdog.cancel(context) }
      runCatching { UploadWorker.cancelPeriodic(context) }
      context.stopService(Intent(context, TrackingService::class.java))
      promise.resolve(null)
    }

    // getState(): snapshot of tracking/permission/services/battery/buffer.
    AsyncFunction("getState") { promise: Promise ->
      scope.launch {
        runCatching {
          val ctx = context
          // Cheap periodic detection: if battery optimization got re-enabled
          // since last check, buffer a BATTERY_OPT_ON event row.
          checkBatteryOptDelta(ctx)
          val bufferedCount = BufferDb.getInstance(ctx).pointDao().count()
          val state = Bundle().apply {
            putBoolean("tracking", TrackingService.isRunning)
            putString("permission", permissionState(ctx))
            putBoolean("locationServices", isLocationEnabled(ctx))
            putBoolean("batteryOptIgnored", isBatteryOptIgnored(ctx))
            putInt("bufferedCount", bufferedCount)
            putInt("killRestarts24h", killRestarts24h(ctx))
          }
          promise.resolve(state)
        }.onFailure { promise.reject("ERR_GET_STATE", it.message, it) }
      }
    }

    // requestPermissions(): request foreground location (+ ACTIVITY_RECOGNITION on
    // 29+, POST_NOTIFICATIONS on 33+) FIRST, then escalate to background location
    // in a SECOND step once foreground is granted, finally resolving the state.
    //
    // Two steps are mandatory: on Android 11+ (API 30+) the system IGNORES the
    // entire request — showing no dialog at all — if ACCESS_BACKGROUND_LOCATION is
    // asked for in the same call as the foreground location permissions.
    AsyncFunction("requestPermissions") { promise: Promise ->
      val permissionsManager = appContext.permissions
        ?: return@AsyncFunction promise.reject(
          "ERR_NO_PERMISSIONS_MANAGER",
          "Permissions manager unavailable",
          null,
        )

      val foreground = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
        }
      }.toTypedArray()

      // Step 1: foreground set. We ignore the manager's aggregate result and
      // resolve our own TrackerState so JS gets a consistent shape.
      permissionsManager.askForPermissions(
        object : expo.modules.interfaces.permissions.PermissionsResponseListener {
          override fun onResult(
            result: MutableMap<String, expo.modules.interfaces.permissions.PermissionsResponse>,
          ) {
            val ctx = context
            val fineGranted = ContextCompat.checkSelfPermission(
              ctx, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val needsBackground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
              ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
              ) != PackageManager.PERMISSION_GRANTED

            // Step 2: only ask for background once foreground is actually granted;
            // requesting it before would be silently denied.
            if (fineGranted && needsBackground) {
              permissionsManager.askForPermissions(
                object : expo.modules.interfaces.permissions.PermissionsResponseListener {
                  override fun onResult(
                    result: MutableMap<String, expo.modules.interfaces.permissions.PermissionsResponse>,
                  ) {
                    resolvePermissionState(promise)
                  }
                },
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
              )
            } else {
              resolvePermissionState(promise)
            }
          }
        },
        *foreground,
      )
    }

    // replay(fromMs, toMs): re-send archived rows for a window with their ORIGINAL
    // ids and times (idempotent on a server that keys by them). Never touches the
    // live buffer; still-unsynced rows in the window go the normal way (nudged).
    AsyncFunction("replay") { fromMs: Double, toMs: Double, promise: Promise ->
      scope.launch {
        runCatching {
          val ctx = context
          val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          val url = prefs.getString(Prefs.KEY_URL, null)
          val token = prefs.getString(Prefs.KEY_TOKEN, null)
          val userId = prefs.getString(Prefs.KEY_USER_ID, "") ?: ""
          if (url.isNullOrBlank() || token.isNullOrBlank() || userId.isBlank()) {
            throw IllegalStateException("tracker not configured (call start first)")
          }
          val batchSize = prefs.getInt(Prefs.KEY_BATCH_SIZE, 50).coerceIn(1, 500)
          val rows = BufferDb.getInstance(ctx).archiveDao()
            .inRange(userId, fromMs.toLong(), toMs.toLong())
          val r = ReplayRunner.run(
            rows = rows,
            url = url,
            initialToken = token,
            batchSize = batchSize,
            provider = TokenProviders.load(prefs),
            onTokenRefreshed = { prefs.edit().putString(Prefs.KEY_TOKEN, it).apply() },
          )
          runCatching { UploadWorker.enqueue(ctx) }
          Bundle().apply {
            putInt("sent", r.sent)
            putInt("accepted", r.accepted)
            putInt("batches", r.batches)
            putInt("failedBatches", r.failedBatches)
          }
        }.onSuccess { promise.resolve(it) }.onFailure {
          when (it) {
            is ReplayRunner.AuthException -> promise.reject("ERR_REPLAY_AUTH", it.message, it)
            is ReplayRunner.BusyException -> promise.reject("ERR_REPLAY_BUSY", it.message, it)
            else -> promise.reject("ERR_REPLAY", it.message, it)
          }
        }
      }
    }

    // getCoverage(fromMs, toMs, gapMs): count/first/last/gaps over the archive UNION
    // the still-unsynced buffer, so a day with pending uploads is not a false gap.
    AsyncFunction("getCoverage") { fromMs: Double, toMs: Double, gapMs: Double, promise: Promise ->
      scope.launch {
        runCatching {
          val ctx = context
          val db = BufferDb.getInstance(ctx)
          val userId = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(Prefs.KEY_USER_ID, "") ?: ""
          val from = fromMs.toLong()
          val to = toMs.toLong()
          val pts = ArrayList<CoverageCalculator.Pt>()
          for (p in db.archiveDao().inRange(userId, from, to)) {
            val la = p.lat ?: continue
            val lo = p.lng ?: continue
            pts += CoverageCalculator.Pt(p.t, la, lo)
          }
          for (p in db.pointDao().locationsInRange(userId, from, to)) {
            val la = p.lat ?: continue
            val lo = p.lng ?: continue
            pts += CoverageCalculator.Pt(p.t, la, lo)
          }
          val gap = if (gapMs > 0) gapMs.toLong() else CoverageCalculator.DEFAULT_GAP_MS
          val r = CoverageCalculator.compute(pts, gap)
          mapOf(
            "count" to r.count,
            "firstT" to r.firstT?.toDouble(),
            "lastT" to r.lastT?.toDouble(),
            "gaps" to r.gaps.map {
              mapOf(
                "fromT" to it.fromT.toDouble(),
                "toT" to it.toT.toDouble(),
                "durationMs" to it.durationMs.toDouble(),
                "distanceM" to it.distanceM,
              )
            },
          )
        }.onSuccess { promise.resolve(it) }
          .onFailure { promise.reject("ERR_GET_COVERAGE", it.message, it) }
      }
    }

    // clearArchive(): privacy hook — the host calls this on sign-out.
    AsyncFunction("clearArchive") { promise: Promise ->
      scope.launch {
        runCatching { BufferDb.getInstance(context).archiveDao().clear() }
          .onSuccess { promise.resolve(null) }
          .onFailure { promise.reject("ERR_CLEAR_ARCHIVE", it.message, it) }
      }
    }

    // getArchiveStats(): row count, oldest timestamp, approximate on-disk size
    // (whole buffer DB including WAL — an upper bound, cheap to read).
    AsyncFunction("getArchiveStats") { promise: Promise ->
      scope.launch {
        runCatching {
          val ctx = context
          val dao = BufferDb.getInstance(ctx).archiveDao()
          val dbFile = ctx.getDatabasePath("livetrack_buffer.db")
          val size = dbFile.length() + File(dbFile.path + "-wal").length()
          Bundle().apply {
            putInt("count", dao.countAll())
            dao.oldestT()?.let { putDouble("oldestT", it.toDouble()) }
            putDouble("sizeBytes", size.toDouble())
          }
        }.onSuccess { promise.resolve(it) }
          .onFailure { promise.reject("ERR_ARCHIVE_STATS", it.message, it) }
      }
    }

    // exportArchive(sinceId, limit): archived rows newer than sinceId, oldest first,
    // for the TEMPORARY Supabase diagnostics upload (src/services/trackingLog.ts).
    // Reads archived_points ONLY — stable/non-racy, unlike `points`, which the
    // uploader deletes from the moment the backend ACKs a row.
    AsyncFunction("exportArchive") { sinceId: Double, limit: Double, promise: Promise ->
      scope.launch {
        runCatching {
          val ctx = context
          val userId = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(Prefs.KEY_USER_ID, "") ?: ""
          val rows = BufferDb.getInstance(ctx).archiveDao()
            .rowsAfterId(userId, sinceId.toLong(), limit.toInt())
          mapOf(
            "rows" to rows.map { archivedPointRecord(it) },
            "lastId" to (rows.lastOrNull()?.id ?: sinceId.toLong()).toDouble(),
          )
        }.onSuccess { promise.resolve(it) }
          .onFailure { promise.reject("ERR_EXPORT_ARCHIVE", it.message, it) }
      }
    }

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
          prefs.edit()
            .putLong(Prefs.KEY_ENSURE_NOT_KILLED_LAST_ATTEMPT_AT, 0L)
            .putInt(Prefs.KEY_ENSURE_NOT_KILLED_ATTEMPT_COUNT, 0)
            .apply()
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
        // Record the attempt before launching the dialog so that even if the launch
        // throws (ActivityNotFoundException on some OEMs, etc), the throttle/cap still
        // engages on the next call — otherwise a throwing device retries forever unthrottled.
        prefs.edit()
          .putLong(Prefs.KEY_ENSURE_NOT_KILLED_LAST_ATTEMPT_AT, now)
          .putInt(Prefs.KEY_ENSURE_NOT_KILLED_ATTEMPT_COUNT, attempts + 1)
          .apply()
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

    // requestEnableLocation(): check location settings; on a resolvable failure,
    // launch the OS "turn on location" dialog via the current Activity and
    // resolve true/false from whether services end up enabled. No self-popup.
    AsyncFunction("requestEnableLocation") { promise: Promise ->
      runCatching {
        val ctx = context
        if (isLocationEnabled(ctx)) {
          promise.resolve(true)
          return@runCatching
        }

        // VERIFY (whole block): LocationServices.getSettingsClient + LocationRequest
        // .Builder + ResolvableApiException.startResolutionForResult against the
        // pinned play-services-location 21.x.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build()
        val settingsRequest = LocationSettingsRequest.Builder()
          .addLocationRequest(request)
          .build()

        val client = LocationServices.getSettingsClient(ctx)
        client.checkLocationSettings(settingsRequest)
          .addOnSuccessListener {
            // Settings already satisfy the request.
            promise.resolve(true)
          }
          .addOnFailureListener { ex ->
            val activity = appContext.currentActivity
            if (ex is ResolvableApiException && activity != null) {
              try {
                // VERIFY: Expo activity-result plumbing. We don't have a registered
                // result contract here, so we launch the resolution and then resolve
                // from a settings re-check rather than the (unavailable) result code.
                // A cleaner impl would use appContext.registerForActivityResult /
                // AppContextActivityResultCaller — API surface differs across
                // expo-modules-core versions and cannot be confirmed in this env.
                ex.startResolutionForResult(activity, REQ_ENABLE_LOCATION)
                // Best-effort: resolve from current state. The host should re-read
                // getState() / listen for the LOCATION_ON event for the truth.
                promise.resolve(isLocationEnabled(ctx))
              } catch (e: Exception) {
                promise.resolve(false)
              }
            } else {
              // Not resolvable (e.g. location mode hard-off) or no activity.
              promise.resolve(false)
            }
          }
      }.onFailure { promise.reject("ERR_REQUEST_ENABLE_LOCATION", it.message, it) }
    }

    // getOemAutostartInfo(): cheap, side-effect-free check for whether this
    // device's manufacturer is a known aggressive background-killer with its
    // own separate "autostart"/"protected apps" allowlist outside Android's
    // standard battery-optimization exemption. The host app uses this to
    // decide whether to show the one-time onboarding prompt at all.
    AsyncFunction("getOemAutostartInfo") { promise: Promise ->
      runCatching {
        val manufacturer = Build.MANUFACTURER ?: ""
        val brand = Build.BRAND ?: ""
        mapOf(
          "manufacturer" to manufacturer,
          "brand" to brand,
          "hasKnownSettings" to OemAutostart.isKnownAggressiveOem(manufacturer, brand),
          "hasBatterySaverSettings" to OemAutostart.isXiaomiFamily(manufacturer, brand),
        )
      }.onSuccess { promise.resolve(it) }
        .onFailure { promise.reject("ERR_OEM_AUTOSTART_INFO", it.message, it) }
    }

    // openBatterySaverSettings(): MIUI/HyperOS per-app battery saver, where
    // "No restrictions" stops MIUI killing the tracker (production: a POCO with
    // it unset was killed ~every 30 min, round the clock). Falls back to the
    // app-info screen, which on MIUI links to the same setting.
    AsyncFunction("openBatterySaverSettings") { promise: Promise ->
      runCatching {
        val ctx = context
        val manufacturer = Build.MANUFACTURER ?: ""
        var attempted = false
        if (OemAutostart.isXiaomiFamily(manufacturer, Build.BRAND ?: "")) {
          val label = runCatching { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }
            .getOrDefault(ctx.packageName)
          val miui = Intent(OemAutostart.MIUI_BATTERY_SAVER_ACTION).apply {
            component = ComponentName(OemAutostart.MIUI_BATTERY_SAVER_PACKAGE, OemAutostart.MIUI_BATTERY_SAVER_CLASS)
            putExtra(OemAutostart.MIUI_EXTRA_PACKAGE_NAME, ctx.packageName)
            putExtra(OemAutostart.MIUI_EXTRA_PACKAGE_LABEL, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          attempted = runCatching { ctx.startActivity(miui) }.isSuccess
        }
        if (!attempted) {
          val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"),
          ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
          runCatching { ctx.startActivity(fallback) }
        }
        mapOf("attempted" to attempted, "manufacturer" to manufacturer)
      }.onSuccess { promise.resolve(it) }
        .onFailure { promise.reject("ERR_OEM_BATTERY_SAVER_OPEN", it.message, it) }
    }

    // openAutostartSettings(): best-effort launch of this OEM's autostart
    // settings screen. Tries each known candidate ComponentName in order
    // (they drift across ROM versions) and falls back to the generic
    // app-info screen so the user always lands somewhere actionable rather
    // than seeing nothing happen.
    AsyncFunction("openAutostartSettings") { promise: Promise ->
      runCatching {
        val ctx = context
        val manufacturer = Build.MANUFACTURER ?: ""
        val candidates = OemAutostart.candidatesFor(manufacturer, Build.BRAND ?: "")
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
  }

  // --- Helpers ------------------------------------------------------------

  /** "granted" (fine + background), "background" (fine only), "denied" otherwise. */
  // Build and resolve the current TrackerState off the main thread. Shared by the
  // (possibly two-step) permission request flow.
  private fun resolvePermissionState(promise: Promise) {
    scope.launch {
      runCatching {
        val ctx = context
        val bufferedCount = BufferDb.getInstance(ctx).pointDao().count()
        val state = Bundle().apply {
          putBoolean("tracking", TrackingService.isRunning)
          putString("permission", permissionState(ctx))
          putBoolean("locationServices", isLocationEnabled(ctx))
          putBoolean("batteryOptIgnored", isBatteryOptIgnored(ctx))
          putInt("bufferedCount", bufferedCount)
          putInt("killRestarts24h", killRestarts24h(ctx))
        }
        promise.resolve(state)
      }.onFailure { promise.reject("ERR_REQUEST_PERMISSIONS", it.message, it) }
    }
  }

  private fun permissionState(ctx: Context): String {
    val fine = ContextCompat.checkSelfPermission(
      ctx, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!fine) return "denied"
    val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true // pre-Q: foreground grant covers background.
    }
    // Per TrackerState type: "granted" = full (incl. background); "background"
    // here means "foreground-only / needs background upgrade".
    return if (background) "granted" else "background"
  }

  /** Silent-kill recoveries of the tracking service in the last 24 h (see [KillStats]). */
  private fun killRestarts24h(ctx: Context): Int =
    KillStats.count(
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(Prefs.KEY_KILL_RESTARTS, null),
      System.currentTimeMillis(),
    )

  private fun isLocationEnabled(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return try {
      lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) {
      false
    }
  }

  private fun isBatteryOptIgnored(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return try {
      pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Compare current location-permission grant to the last-seen value and buffer
   * a PERMISSION_GRANTED / PERMISSION_REVOKED event row on a change. Runs on IO.
   */
  private fun checkPermissionDelta(ctx: Context) {
    val granted = permissionState(ctx) == "granted"
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val hadValue = prefs.contains(Prefs.KEY_LAST_PERMISSION_GRANTED)
    val last = prefs.getBoolean(Prefs.KEY_LAST_PERMISSION_GRANTED, granted)
    prefs.edit().putBoolean(Prefs.KEY_LAST_PERMISSION_GRANTED, granted).apply()
    if (hadValue && last != granted) {
      bufferEvent(ctx, if (granted) "PERMISSION_GRANTED" else "PERMISSION_REVOKED")
    }
  }

  /**
   * If battery optimization got re-enabled since the last check, buffer a
   * BATTERY_OPT_ON event row (kept simple; driven from getState()).
   */
  private fun checkBatteryOptDelta(ctx: Context) {
    val ignored = isBatteryOptIgnored(ctx)
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val hadValue = prefs.contains(Prefs.KEY_LAST_BATTERY_OPT_IGNORED)
    val last = prefs.getBoolean(Prefs.KEY_LAST_BATTERY_OPT_IGNORED, ignored)
    prefs.edit().putBoolean(Prefs.KEY_LAST_BATTERY_OPT_IGNORED, ignored).apply()
    // Transition from exempt -> optimized again is the noteworthy event.
    if (hadValue && last && !ignored) {
      bufferEvent(ctx, "BATTERY_OPT_ON")
    }
  }

  /** Insert a tier-2 event row + emit onEvent (best-effort). Caller is on IO. */
  private fun bufferEvent(ctx: Context, eventType: String) {
    val userId = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString(Prefs.KEY_USER_ID, "") ?: ""
    val now = System.currentTimeMillis()
    runCatching {
      BufferDb.getInstance(ctx).pointDao().insert(
        PointEntity(userId = userId, t = now, eventType = eventType),
      )
    }
    val payload = Bundle().apply {
      putString("e", eventType)
      putDouble("t", now.toDouble())
    }
    runCatching { LiveTrackEventBus.emit(LiveTrackEventBus.EVENT_EVENT, payload) }
  }

  /** Wire shape matching `LocationRecord` in LiveTrack.types.ts (l/g/t/s/acc/b/c/act/mock). */
  private fun archivedPointRecord(p: ArchivedPointEntity): Map<String, Any?> = mapOf(
    "l" to (p.lat ?: 0.0),
    "g" to (p.lng ?: 0.0),
    "t" to p.t.toDouble(),
    "s" to p.speed,
    "acc" to p.acc,
    "b" to p.batt,
    "c" to p.charging,
    "act" to (p.act ?: "UNKNOWN"),
    "mock" to p.mock,
  )

  private fun numLong(v: Any?, default: Long): Long = when (v) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull() ?: default
    else -> default
  }

  private fun numDouble(v: Any?, default: Double): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: default
    else -> default
  }

  companion object {
    private const val PREFS = "livetrack_prefs"
    private const val REQ_ENABLE_LOCATION = 3003
    /** ensureNotKilled() throttle: matches the app's own in-app banner cadence. */
    private const val ENSURE_NOT_KILLED_COOLDOWN_MS = 10 * 60 * 1000L
    /** ensureNotKilled() cap: consecutive un-confirmed attempts before giving up. */
    private const val ENSURE_NOT_KILLED_MAX_ATTEMPTS = 3
  }
}
