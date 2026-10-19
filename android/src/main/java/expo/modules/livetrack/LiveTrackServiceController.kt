package expo.modules.livetrack

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.ContextCompat
import expo.modules.livetrack.keepalive.Watchdog

/**
 * Small helper that rebuilds a [TrackingService] start intent from persisted
 * config, so the module, [BootReceiver] and the watchdog all start the service
 * the same way without duplicating the extras wiring.
 */
object LiveTrackServiceController {

  /** Whether the foreground capture service is currently running. */
  fun isRunning(): Boolean = TrackingService.isRunning

  /**
   * Process-local; a fresh process after a kill always gets one immediate
   * attempt. Retries are spaced so a start that keeps failing (e.g. location
   * permission revoked) can't turn every AR delivery into a wake-up.
   */
  private var lastRecoveryAttemptAt = 0L
  private const val RECOVERY_MIN_GAP_MS = 5 * 60_000L

  /**
   * An Activity Recognition result arrived but no service is running in this
   * process. If we should be tracking, restart now (reason `ar_restart`); AR
   * transition events are one of Android 12+'s allowed reasons to start a
   * foreground service from the background. The watchdog is pulled forward as a
   * backstop in case the start is refused or the OEM kills us again mid-start.
   * After sign-out, AR is unregistered instead so nothing keeps waking us.
   */
  fun recoverFromActivityEvent(context: Context) {
    val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(Prefs.KEY_WAS_TRACKING, false)) {
      ActivityRegistrations.removeAll(context)
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (lastRecoveryAttemptAt != 0L && now - lastRecoveryAttemptAt < RECOVERY_MIN_GAP_MS) return
    lastRecoveryAttemptAt = now
    runCatching {
      ContextCompat.startForegroundService(context, buildStartIntent(context, prefs, "ar_restart"))
    }
    runCatching { Watchdog.scheduleSoon(context) }
  }

  /**
   * Build a start intent for [TrackingService] from persisted prefs.
   *
   * @param reason why the service is being (re)started — surfaces in the
   *   SERVICE_STARTED lifecycle event so kills and restarts become observable.
   */
  fun buildStartIntent(context: Context, prefs: SharedPreferences, reason: String): Intent {
    return Intent(context, TrackingService::class.java).apply {
      putExtra(TrackingService.EXTRA_START_REASON, reason)
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
