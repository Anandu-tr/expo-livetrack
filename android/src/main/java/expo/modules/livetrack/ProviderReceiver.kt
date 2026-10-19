package expo.modules.livetrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import expo.modules.livetrack.buffer.BufferDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reacts to GPS being toggled (`android.location.PROVIDERS_CHANGED`).
 *
 * MUST stay at `expo.modules.livetrack.ProviderReceiver` — the config plugin
 * emits exactly this fully-qualified name into the manifest (pinned by
 * `plugin.test.ts`), so it cannot move to a subpackage.
 *
 * On a real provider state change it inserts a tier-2 event row
 * (`LOCATION_OFF` / `LOCATION_ON`) into the buffer (with last-known position +
 * battery when available) and emits `onEvent` for the UI, via
 * [DeviceStateEvents], which also reconciles changes this receiver missed.
 *
 * VERIFY: on recent Android, PROVIDERS_CHANGED is frequently NOT delivered to
 * *manifest-declared* receivers for background apps (implicit-broadcast
 * restrictions). [TrackingService] therefore ALSO registers an instance of this
 * receiver at runtime via [register] while it is alive — the manifest entry is
 * kept as a best-effort fallback (and because the plugin declares it).
 */
class ProviderReceiver : BroadcastReceiver() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
    // goAsync keeps the process alive briefly so the buffer insert can finish
    // even on the manifest-delivered path (where there's no service holding us up).
    val pending = goAsync()
    val appCtx = context.applicationContext
    scope.launch {
      try {
        handle(appCtx)
      } finally {
        runCatching { pending.finish() }
      }
    }
  }

  private fun handle(context: Context) {
    // Runs inside an IO coroutine (from onReceive's goAsync), so the insert
    // completes before goAsync().finish().
    //
    // PROVIDERS_CHANGED is delivered several times per toggle (once per provider,
    // and to both the manifest and the runtime-registered instance), concurrently:
    // six identical LOCATION_OFF rows in one second were observed in production.
    // DeviceStateEvents makes the compare-and-record atomic, and records the
    // first observation as a baseline.
    DeviceStateEvents.checkLocation(context, BufferDb.getInstance(context).pointDao(), reason = null)
  }

  companion object {
    /**
     * Register a runtime instance bound to [TrackingService]'s lifetime so the
     * PROVIDERS_CHANGED broadcast is reliably received even when manifest
     * delivery is suppressed. Returns the instance so the caller can unregister.
     */
    fun register(context: Context): ProviderReceiver {
      val receiver = ProviderReceiver()
      val filter = android.content.IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
      // VERIFY: RECEIVER_NOT_EXPORTED required arg on Android 14 (UPSIDE_DOWN_CAKE+)
      // for context-registered receivers of non-system... this is a system
      // broadcast, but the flag is still required to register at all on 34+.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.registerReceiver(
          context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
      } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
      }
      return receiver
    }
  }
}
