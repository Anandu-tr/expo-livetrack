package expo.modules.livetrack

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransitionRequest

/**
 * Activity Recognition (AR) registration, shared by [TrackingService] (register
 * while tracking) and [ActivityReceiver] (unregister after sign-out).
 *
 * Results are delivered as broadcasts to [ActivityReceiver]. Builds before Oct
 * 2026 used `PendingIntent.getService` instead: after a process death each AR
 * delivery re-created a bare TrackingService that never went foreground (seen
 * in production as location-less heartbeats for ~20 min until the watchdog
 * fired), and background service starts are exactly what MIUI and Android
 * restrict. Those legacy registrations are removed whenever we (re)register.
 *
 * PendingIntents are rebuilt deterministically (same request code + explicit
 * intent), so no references need to be kept between calls. Every GMS call can
 * throw SecurityException without ACTIVITY_RECOGNITION; callers decide what to
 * do, except [removeAll], which is best-effort.
 */
internal object ActivityRegistrations {

  /** How often AR is asked to sample the current activity. */
  const val SAMPLE_INTERVAL_MS = 10_000L

  private const val REQ_TRANSITION = 1001
  private const val REQ_SAMPLE = 1002

  /** Register transition + sampling updates, replacing legacy service-targeted ones. */
  fun request(context: Context, transitions: ActivityTransitionRequest) {
    removeLegacy(context)
    val client = ActivityRecognition.getClient(context)
    client.requestActivityTransitionUpdates(
      transitions, broadcast(context, TrackingService.ACTION_ACTIVITY_TRANSITION, REQ_TRANSITION),
    )
    client.requestActivityUpdates(
      SAMPLE_INTERVAL_MS, broadcast(context, TrackingService.ACTION_ACTIVITY_SAMPLE, REQ_SAMPLE),
    )
  }

  /** Stop all AR deliveries to this app, current and legacy. Best-effort. */
  fun removeAll(context: Context) {
    val client = ActivityRecognition.getClient(context)
    runCatching {
      client.removeActivityTransitionUpdates(
        broadcast(context, TrackingService.ACTION_ACTIVITY_TRANSITION, REQ_TRANSITION),
      )
    }
    runCatching {
      client.removeActivityUpdates(broadcast(context, TrackingService.ACTION_ACTIVITY_SAMPLE, REQ_SAMPLE))
    }
    removeLegacy(context)
  }

  private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      requestCode,
      // Explicit component: required for a mutable PendingIntent on Android 14+,
      // and mutable is required so Play services can attach the result.
      Intent(context, ActivityReceiver::class.java).setAction(action),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

  private fun removeLegacy(context: Context) {
    val client = ActivityRecognition.getClient(context)
    legacyService(context, TrackingService.ACTION_ACTIVITY_TRANSITION, REQ_TRANSITION)?.let { pi ->
      runCatching { client.removeActivityTransitionUpdates(pi) }
      pi.cancel()
    }
    legacyService(context, TrackingService.ACTION_ACTIVITY_SAMPLE, REQ_SAMPLE)?.let { pi ->
      runCatching { client.removeActivityUpdates(pi) }
      pi.cancel()
    }
  }

  /** The pre-Oct-2026 service PendingIntent, if one still exists (never creates it). */
  private fun legacyService(context: Context, action: String, requestCode: Int): PendingIntent? =
    PendingIntent.getService(
      context,
      requestCode,
      Intent(context, TrackingService::class.java).setAction(action),
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE,
    )
}
