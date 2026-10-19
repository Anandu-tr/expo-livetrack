package expo.modules.livetrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives Activity Recognition results (see [ActivityRegistrations]).
 *
 * Declared in this module's own AndroidManifest.xml (merged into the host app),
 * not by the config plugin. Not exported: the PendingIntent is sent with this
 * app's identity.
 *
 * While tracking, results go straight to the live [TrackingService]. If none is
 * running here, the process died while tracking (AR registrations outlive it):
 * that is the earliest signal we get, so tracking is recovered now rather than
 * whenever the ~15-min watchdog fires.
 */
class ActivityReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action != TrackingService.ACTION_ACTIVITY_TRANSITION &&
      action != TrackingService.ACTION_ACTIVITY_SAMPLE
    ) {
      return
    }
    // onReceive runs on the main thread, which is the service's threading model.
    val service = TrackingService.instance
    if (service != null && TrackingService.isRunning) {
      service.onActivityIntent(intent)
      return
    }
    LiveTrackServiceController.recoverFromActivityEvent(context.applicationContext)
  }
}
