package expo.modules.livetrack.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import expo.modules.livetrack.BootReceiver

/**
 * Periodic keep-alive. Every ~15 min an AlarmManager alarm fires an explicit
 * intent at [BootReceiver]; the receiver checks the was-tracking flag and
 * restarts [expo.modules.livetrack.TrackingService] if it should be running but
 * isn't, then this watchdog reschedules itself.
 *
 * Why target [BootReceiver] (not a new receiver): a brand-new receiver class
 * would NOT be in the config-plugin manifest and would never fire. An *explicit*
 * intent (component set) is delivered regardless of intent-filters, so reusing
 * the already-declared, exported BootReceiver is safe. The receiver treats
 * [ACTION_WATCHDOG] the same as a boot.
 */
object Watchdog {

  const val ACTION_WATCHDOG = "expo.modules.livetrack.ACTION_WATCHDOG"
  private const val REQ_WATCHDOG = 2002
  private const val INTERVAL_MS = 15 * 60 * 1000L // ~15 min

  fun schedule(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val pi = pendingIntent(context)
    val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

    // Exact-while-idle is best for reliability, but on Android 12+ it needs the
    // SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM permission and may be revoked by the
    // user. Gate on canScheduleExactAlarms() and fall back to the inexact
    // (but Doze-friendly) variant otherwise.
    // VERIFY: canScheduleExactAlarms()/setExactAndAllowWhileIdle availability +
    // whether the host app declares (USE|SCHEDULE)_EXACT_ALARM. The android/
    // AndroidManifest.xml in this module declares them as a best effort.
    try {
      val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        am.canScheduleExactAlarms()
      } else {
        true
      }
      if (canExact) {
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
      } else {
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
      }
    } catch (e: SecurityException) {
      // Exact denied at runtime — degrade gracefully.
      runCatching {
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
      }
    }
  }

  fun cancel(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    am.cancel(pendingIntent(context))
  }

  private fun pendingIntent(context: Context): PendingIntent {
    // Explicit component → delivered to BootReceiver regardless of its filters.
    val intent = Intent(context, BootReceiver::class.java).apply {
      action = ACTION_WATCHDOG
    }
    // FLAG_IMMUTABLE required on API 23+. // VERIFY: flag combination.
    return PendingIntent.getBroadcast(
      context,
      REQ_WATCHDOG,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
