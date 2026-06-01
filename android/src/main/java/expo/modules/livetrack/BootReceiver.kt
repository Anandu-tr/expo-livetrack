package expo.modules.livetrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import expo.modules.livetrack.keepalive.Watchdog
import expo.modules.livetrack.sync.UploadWorker

/**
 * Re-arms tracking after a reboot or an app update.
 *
 * MUST stay at `expo.modules.livetrack.BootReceiver` — the config plugin emits
 * exactly this fully-qualified name into the manifest (and `plugin.test.ts`
 * pins it), so moving it to a subpackage would yield a ClassNotFoundException
 * and a dead receiver.
 *
 * Intent filters (declared by the plugin): BOOT_COMPLETED + MY_PACKAGE_REPLACED,
 * exported=true.
 */
class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action
    val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
      action == Intent.ACTION_MY_PACKAGE_REPLACED ||
      // Watchdog targets this receiver explicitly with its own action.
      action == Watchdog.ACTION_WATCHDOG ||
      action == null // explicit-component start

    if (!relevant) return

    val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(Prefs.KEY_WAS_TRACKING, false)) return

    // Only (re)start if not already running — avoids redundant foreground starts.
    if (LiveTrackServiceController.isRunning()) {
      // Service alive; still make sure the safety-net + watchdog are scheduled.
      reschedule(context)
      return
    }

    val serviceIntent = LiveTrackServiceController.buildStartIntent(context, prefs)
    runCatching {
      // VERIFY: background-start-from-receiver constraints on Android 12+/14:
      // starting a location FGS from BOOT_COMPLETED is explicitly allowed
      // (boot is one of the documented exemptions); MY_PACKAGE_REPLACED and the
      // watchdog alarm rely on the FGS-launch-while-in-background exemptions and
      // may throw ForegroundServiceStartNotAllowedException on some OEMs — hence
      // the runCatching guard so a denial can't crash the receiver.
      ContextCompat.startForegroundService(context, serviceIntent)
    }
    reschedule(context)
  }

  private fun reschedule(context: Context) {
    runCatching { Watchdog.schedule(context) }
    runCatching { UploadWorker.enqueuePeriodic(context) }
  }
}
