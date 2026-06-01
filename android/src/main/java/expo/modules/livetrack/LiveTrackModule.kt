package expo.modules.livetrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.livetrack.buffer.BufferDb
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
    Function("start") { config: Map<String, Any?> ->
      val url = config["url"] as? String ?: ""
      val token = config["token"] as? String ?: ""
      val userId = config["userId"] as? String ?: ""

      @Suppress("UNCHECKED_CAST")
      val cadence = config["cadence"] as? Map<String, Any?> ?: emptyMap()
      val movingIntervalMs = numLong(cadence["movingIntervalMs"], 12_000L)
      val movingDistanceM = numDouble(cadence["movingDistanceM"], 30.0).toFloat()
      val stillIntervalMs = numLong(cadence["stillIntervalMs"], 120_000L)
      val maxAccuracyM = numDouble(cadence["maxAccuracyM"], 50.0)

      // Persist for the (future) uploader + reboot re-arm.
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
        putString("url", url)
        putString("token", token)
        putString("userId", userId)
        putLong("movingIntervalMs", movingIntervalMs)
        putFloat("movingDistanceM", movingDistanceM)
        putLong("stillIntervalMs", stillIntervalMs)
        putFloat("maxAccuracyM", maxAccuracyM.toFloat())
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
    }

    // stop(): stop the foreground service.
    Function("stop") {
      context.stopService(Intent(context, TrackingService::class.java))
    }

    // getState(): snapshot of tracking/permission/services/battery/buffer.
    AsyncFunction("getState") { promise: Promise ->
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
        }.onFailure { promise.reject("ERR_GET_STATE", it.message, it) }
      }
    }

    // requestPermissions(): request fine + background location (+ POST_NOTIFICATIONS
    // on 33+, ACTIVITY_RECOGNITION on 29+), then resolve the resulting state.
    AsyncFunction("requestPermissions") { promise: Promise ->
      val permissionsManager = appContext.permissions
        ?: return@AsyncFunction promise.reject(
          "ERR_NO_PERMISSIONS_MANAGER",
          "Permissions manager unavailable",
          null,
        )

      val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // Background location must usually be requested separately/after foreground
        // on API 30+; we include it here for simplicity. // VERIFY: on Android 11+
        // the OS may ignore background in the same prompt — host may need a 2-step flow.
        add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
        }
      }.toTypedArray()

      // VERIFY: Permissions.askForPermissionsWithPermissionsManager(manager, promise, vararg).
      // We ignore the manager's aggregate result and resolve our own TrackerState
      // so JS gets a consistent shape; the callback fires after the OS dialog.
      Permissions.askForPermissionsWithPermissionsManager(
        permissionsManager,
        object : expo.modules.interfaces.permissions.PermissionsResponseListener {
          override fun onResult(
            // VERIFY: Java interface declares Map<String, PermissionsResponse>.
            result: MutableMap<String, expo.modules.interfaces.permissions.PermissionsResponse>,
          ) {
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
        },
        *perms,
      )
    }

    // --- Stubs: implemented in the next task -----------------------------

    AsyncFunction("ensureNotKilled") { promise: Promise ->
      // TODO next task: open battery-optimization-ignore intent / verify service alive.
      promise.reject(
        "ERR_NOT_IMPLEMENTED",
        "ensureNotKilled() is implemented in the next task (battery-opt intent).",
        null,
      )
    }

    AsyncFunction("requestEnableLocation") { promise: Promise ->
      // TODO next task: SettingsClient.checkLocationSettings + ResolvableApiException UI.
      promise.reject(
        "ERR_NOT_IMPLEMENTED",
        "requestEnableLocation() is implemented in the next task (SettingsClient).",
        null,
      )
    }
  }

  // --- Helpers ------------------------------------------------------------

  /** "granted" (fine + background), "background" (fine only), "denied" otherwise. */
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
  }
}
