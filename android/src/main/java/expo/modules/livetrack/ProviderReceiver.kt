package expo.modules.livetrack

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import expo.modules.livetrack.buffer.BufferDb
import expo.modules.livetrack.buffer.PointEntity
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
 * battery when available) and emits `onEvent` for the UI. Duplicate consecutive
 * states are debounced via a pref.
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
    // Runs inside an IO coroutine (from onReceive's goAsync, or the caller's).
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
      .getOrDefault(false)

    val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    // Debounce: only act on an actual transition. -1 = unknown (first observation).
    val last = if (prefs.contains(Prefs.KEY_LAST_LOCATION_ENABLED)) {
      prefs.getBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, true)
    } else {
      null
    }
    if (last != null && last == enabled) return
    prefs.edit().putBoolean(Prefs.KEY_LAST_LOCATION_ENABLED, enabled).apply()

    val userId = prefs.getString(Prefs.KEY_USER_ID, "") ?: ""
    val eventType = if (enabled) "LOCATION_ON" else "LOCATION_OFF"
    val now = System.currentTimeMillis()

    val (lat, lng) = lastKnown(context, lm)
    val batt = readBatteryLevel(context)

    val row = PointEntity(
      userId = userId,
      t = now,
      lat = lat,
      lng = lng,
      batt = batt,
      eventType = eventType,
    )
    val dao = BufferDb.getInstance(context).pointDao()
    // Already on Dispatchers.IO (caller's coroutine) — insert inline so a
    // goAsync().finish() doesn't race ahead of the write.
    runCatching { dao.insert(row) }

    val payload = Bundle().apply {
      putString("e", eventType)
      putDouble("t", now.toDouble())
      lat?.let { putDouble("l", it) }
      lng?.let { putDouble("g", it) }
      putInt("b", batt)
    }
    runCatching { LiveTrackEventBus.emit(LiveTrackEventBus.EVENT_EVENT, payload) }

    // A LOCATION_ON event is a good moment to flush whatever queued up.
    if (enabled) runCatching { expo.modules.livetrack.sync.UploadWorker.enqueue(context) }
  }

  private fun lastKnown(context: Context, lm: LocationManager): Pair<Double?, Double?> {
    val fineGranted = ContextCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!fineGranted && !coarseGranted) return null to null
    return try {
      // VERIFY: getLastKnownLocation may return null; guarded by permission check.
      val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
      loc?.latitude to loc?.longitude
    } catch (e: SecurityException) {
      null to null
    }
  }

  private fun readBatteryLevel(context: Context): Int {
    return try {
      val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
      bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (e: Exception) {
      -1
    }
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
