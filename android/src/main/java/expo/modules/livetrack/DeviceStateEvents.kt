package expo.modules.livetrack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import expo.modules.livetrack.buffer.BufferDb
import expo.modules.livetrack.buffer.PointDao
import expo.modules.livetrack.buffer.PointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Device-state changes recorded as event rows: location services on/off
 * (LOCATION_ON/OFF), location permission (PERMISSION_GRANTED/REVOKED) and battery
 * optimisation re-enabled (BATTERY_OPT_ON).
 *
 * Live callers record a change as it happens: [ProviderReceiver] on
 * PROVIDERS_CHANGED, the module on start()/getState(). [reconcile] runs on every
 * service start and once a minute, because the live paths miss changes made
 * while nothing was listening. Production, POCO/MIUI (Oct 2026): location was
 * switched on while the killed service had no receiver, so no LOCATION_ON was
 * ever recorded and the stale state later produced a false ON+OFF pair at the
 * next switch-off; permission and battery changes were only noticed when the app
 * was opened. A change found by [reconcile] carries `r = "reconcile"`: its time
 * is when it was noticed, not when it happened.
 *
 * Every check is an atomic read-compare-write of its "last seen" pref (one lock,
 * synchronous commit), so concurrent callers record a change once. The check
 * functions insert into the database: call them off the main thread.
 */
internal object DeviceStateEvents {

  const val REASON_RECONCILE = "reconcile"

  private val LOCK = Any()

  /** Own scope: a reconcile started by a service that is about to stop still finishes. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** [reconcile] on a background thread. */
  fun reconcileAsync(context: Context) {
    val app = context.applicationContext
    scope.launch { runCatching { reconcile(app, BufferDb.getInstance(app).pointDao()) } }
  }

  fun reconcile(context: Context, dao: PointDao) {
    checkLocation(context, dao, REASON_RECONCILE)
    checkPermission(context, dao, REASON_RECONCILE)
    checkBatteryOpt(context, dao, REASON_RECONCILE)
  }

  /**
   * Record LOCATION_ON/OFF if the GPS provider's state differs from the last one
   * recorded. The first observation is recorded too, as a baseline.
   * @return true if a row was recorded.
   */
  fun checkLocation(context: Context, dao: PointDao, reason: String?): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    val enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    if (!changed(context, Prefs.KEY_LAST_LOCATION_ENABLED, enabled, recordFirst = true)) return false
    val (lat, lng) = lastKnown(context, lm)
    record(context, dao, if (enabled) "LOCATION_ON" else "LOCATION_OFF", reason, lat, lng)
    return true
  }

  /**
   * Record PERMISSION_GRANTED/REVOKED when full location access (fine +
   * background) changed since last seen. No event on the first observation.
   */
  fun checkPermission(context: Context, dao: PointDao, reason: String?): Boolean {
    val granted = locationPermission(context) == "granted"
    if (!changed(context, Prefs.KEY_LAST_PERMISSION_GRANTED, granted, recordFirst = false)) return false
    record(context, dao, if (granted) "PERMISSION_GRANTED" else "PERMISSION_REVOKED", reason, null, null)
    return true
  }

  /** Record BATTERY_OPT_ON when an exemption was lost; gaining one only updates the last-seen value. */
  fun checkBatteryOpt(context: Context, dao: PointDao, reason: String?): Boolean {
    val ignored = isBatteryOptIgnored(context)
    val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    val reEnabled = synchronized(LOCK) {
      val hadValue = prefs.contains(Prefs.KEY_LAST_BATTERY_OPT_IGNORED)
      val last = prefs.getBoolean(Prefs.KEY_LAST_BATTERY_OPT_IGNORED, ignored)
      if (!hadValue || last != ignored) {
        prefs.edit().putBoolean(Prefs.KEY_LAST_BATTERY_OPT_IGNORED, ignored).commit()
      }
      hadValue && last && !ignored
    }
    if (!reEnabled) return false
    record(context, dao, "BATTERY_OPT_ON", reason, null, null)
    return true
  }

  /** "granted" (fine + background), "background" (fine only: needs the background upgrade), "denied". */
  fun locationPermission(ctx: Context): String {
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
    return if (background) "granted" else "background"
  }

  fun isBatteryOptIgnored(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return try {
      pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Atomically compare [current] with the value last stored under [key] and store
   * it. @return true when it differs, or (with [recordFirst]) when none was stored.
   */
  private fun changed(context: Context, key: String, current: Boolean, recordFirst: Boolean): Boolean {
    val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    synchronized(LOCK) {
      val hadValue = prefs.contains(key)
      if (hadValue && prefs.getBoolean(key, current) == current) return false
      prefs.edit().putBoolean(key, current).commit()
      return hadValue || recordFirst
    }
  }

  private fun record(
    context: Context,
    dao: PointDao,
    type: String,
    reason: String?,
    lat: Double?,
    lng: Double?,
  ) {
    val userId = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
      .getString(Prefs.KEY_USER_ID, "") ?: ""
    val now = System.currentTimeMillis()
    val batt = batteryLevel(context)
    // `act` carries the reason and is uploaded as the event's `r`.
    runCatching {
      dao.insert(PointEntity(userId = userId, t = now, lat = lat, lng = lng, batt = batt, act = reason, eventType = type))
    }
    val payload = Bundle().apply {
      putString("e", type)
      putDouble("t", now.toDouble())
      lat?.let { putDouble("l", it) }
      lng?.let { putDouble("g", it) }
      putInt("b", batt)
      reason?.let { putString("r", it) }
    }
    runCatching { LiveTrackEventBus.emit(LiveTrackEventBus.EVENT_EVENT, payload) }
    // Upload right away so the change reaches the server close to when it was seen.
    runCatching { expo.modules.livetrack.sync.UploadWorker.enqueue(context) }
  }

  private fun lastKnown(context: Context, lm: LocationManager): Pair<Double?, Double?> {
    if (locationPermission(context) == "denied" &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return null to null
    }
    return try {
      val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
      loc?.latitude to loc?.longitude
    } catch (e: SecurityException) {
      null to null
    }
  }

  private fun batteryLevel(context: Context): Int = try {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
  } catch (e: Exception) {
    -1
  }
}
