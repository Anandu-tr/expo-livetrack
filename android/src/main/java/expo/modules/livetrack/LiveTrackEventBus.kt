package expo.modules.livetrack

import android.os.Bundle

/**
 * Same-process bridge between [TrackingService] (the capture/event producer) and
 * [LiveTrackModule] (the JS event emitter).
 *
 * The service and the Expo runtime run in the SAME process (no `android:process`
 * on the service in the config-plugin manifest), so no IPC/broadcast is needed —
 * a process-wide singleton holding a nullable listener is sufficient and avoids
 * the deprecated LocalBroadcastManager + an extra dependency.
 *
 * Emission is BEST-EFFORT for UI only. The source of truth is the Room buffer +
 * (future) uploader; if no module is currently attached (JS not alive), events
 * are simply dropped — the data is already persisted in the buffer.
 */
object LiveTrackEventBus {
  /** Names mirror the JS event keys in LiveTrack.types.ts. */
  const val EVENT_LOCATION = "onLocation"
  const val EVENT_EVENT = "onEvent"
  const val EVENT_SYNC_ERROR = "onSyncError"

  /** Receives (eventName, payload) tuples to forward to JS. */
  fun interface Listener {
    fun onEmit(eventName: String, payload: Bundle)
  }

  @Volatile
  private var listener: Listener? = null

  fun setListener(l: Listener?) {
    listener = l
  }

  fun emit(eventName: String, payload: Bundle) {
    // Snapshot to a local to avoid a race with setListener(null).
    listener?.onEmit(eventName, payload)
  }
}
