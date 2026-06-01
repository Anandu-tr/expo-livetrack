package expo.modules.livetrack

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Small helper that rebuilds a [TrackingService] start intent from persisted
 * config, so the module, [BootReceiver] and the watchdog all start the service
 * the same way without duplicating the extras wiring.
 */
object LiveTrackServiceController {

  /** Whether the foreground capture service is currently running. */
  fun isRunning(): Boolean = TrackingService.isRunning

  /** Build a start intent for [TrackingService] from persisted prefs. */
  fun buildStartIntent(context: Context, prefs: SharedPreferences): Intent {
    return Intent(context, TrackingService::class.java).apply {
      putExtra(TrackingService.EXTRA_URL, prefs.getString(Prefs.KEY_URL, "") ?: "")
      putExtra(TrackingService.EXTRA_TOKEN, prefs.getString(Prefs.KEY_TOKEN, "") ?: "")
      putExtra(TrackingService.EXTRA_USER_ID, prefs.getString(Prefs.KEY_USER_ID, "") ?: "")
      putExtra(
        TrackingService.EXTRA_MOVING_INTERVAL_MS,
        prefs.getLong(Prefs.KEY_MOVING_INTERVAL_MS, 12_000L),
      )
      putExtra(
        TrackingService.EXTRA_MOVING_DISTANCE_M,
        prefs.getFloat(Prefs.KEY_MOVING_DISTANCE_M, 30f),
      )
      putExtra(
        TrackingService.EXTRA_STILL_INTERVAL_MS,
        prefs.getLong(Prefs.KEY_STILL_INTERVAL_MS, 120_000L),
      )
      putExtra(
        TrackingService.EXTRA_MAX_ACCURACY_M,
        prefs.getFloat(Prefs.KEY_MAX_ACCURACY_M, 50f).toDouble(),
      )
    }
  }
}
