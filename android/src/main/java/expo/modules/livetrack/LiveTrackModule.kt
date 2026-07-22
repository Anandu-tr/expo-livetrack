package expo.modules.livetrack

import android.Manifest
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
import expo.modules.livetrack.buffer.BufferDb
import expo.modules.livetrack.buffer.PointEntity
import expo.modules.livetrack.keepalive.Watchdog
import expo.modules.livetrack.sync.UploadWorker
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
      val batchSize = numLong(cadence["batchSize"], 50L).toInt()

      val tokenProviderClass = config["tokenProviderClass"] as? String
      @Suppress("UNCHECKED_CAST")
      val diagnostics = config["diagnostics"] as? Map<String, Any?> ?: emptyMap()
      val crashlyticsOn = diagnostics["crashlytics"] as? Boolean ?: false

      // Persist for the uploader + reboot re-arm. wasTracking lets BootReceiver
      // / Watchdog know whether to resume after a reboot or process death.
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
        putString("url", url)
        putString("token", token)
        putString("userId", userId)
        putLong("movingIntervalMs", movingIntervalMs)
        putFloat("movingDistanceM", movingDistanceM)
        putLong("stillIntervalMs", stillIntervalMs)
        putFloat("maxAccuracyM", maxAccuracyM.toFloat())
        putInt("batchSize", batchSize)
        putBoolean(Prefs.KEY_DIAGNOSTICS_CRASHLYTICS, crashlyticsOn)
        putString(Prefs.KEY_TOKEN_PROVIDER_CLASS, tokenProviderClass)
        putBoolean(Prefs.KEY_WAS_TRACKING, true)
        apply()
      }

      val intent = Intent(context, TrackingService::class.java).apply {
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
  }
}
