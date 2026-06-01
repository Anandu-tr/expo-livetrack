package expo.modules.livetrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import expo.modules.livetrack.buffer.BufferDb
import expo.modules.livetrack.buffer.PointDao
import expo.modules.livetrack.buffer.PointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that captures locations into the Room buffer.
 *
 * Class name is referenced verbatim by the config plugin's manifest entry
 * (`expo.modules.livetrack.TrackingService`, foregroundServiceType=location) — do
 * NOT rename.
 *
 * Responsibilities (this slice):
 *  - Persistent low-importance foreground notification (channel `livetrack`).
 *  - FusedLocation updates at a motion-adaptive cadence.
 *  - Buffer each fix as a [PointEntity] (ALWAYS capturing accuracy/speed/mock).
 *  - Activity-recognition transitions to switch moving<->still cadence and emit
 *    HEARTBEAT rows while still.
 *
 * It does NOT upload — the uploader is a later task (see TODO below).
 */
class TrackingService : Service() {

  // IO scope for all DB writes; cancelled in onDestroy.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private lateinit var fused: FusedLocationProviderClient
  private val dao: PointDao by lazy { BufferDb.getInstance(applicationContext).pointDao() }

  // Resolved from start-intent extras.
  private var userId: String = ""
  private var movingIntervalMs: Long = 12_000L
  private var movingDistanceM: Float = 30f
  private var stillIntervalMs: Long = 120_000L
  private var maxAccuracyM: Double = 50.0

  // Motion state.
  @Volatile private var isStill: Boolean = false
  @Volatile private var currentActivity: String = "UNKNOWN"
  // Last known position, used to give HEARTBEAT rows context.
  @Volatile private var lastLat: Double? = null
  @Volatile private var lastLng: Double? = null
  private var lastHeartbeatAt: Long = 0L

  private var activityPendingIntent: PendingIntent? = null

  // Runtime-registered so PROVIDERS_CHANGED is reliably delivered while we're
  // alive (manifest delivery of this implicit broadcast is often suppressed).
  private var providerReceiver: expo.modules.livetrack.ProviderReceiver? = null

  private val locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      for (loc in result.locations) {
        handleLocation(loc)
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    fused = LocationServices.getFusedLocationProviderClient(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // The activity-recognition PendingIntent targets THIS service (not a
    // BroadcastReceiver, which is a later task), so transition deliveries arrive
    // here as a re-delivered start intent with ACTION_ACTIVITY_TRANSITION.
    if (intent?.action == ACTION_ACTIVITY_TRANSITION) {
      handleActivityTransition(intent)
      return START_STICKY
    }

    // Normal start: read config + go foreground.
    intent?.let { readConfig(it) }

    // Guard against Android 14 ForegroundServiceStartNotAllowedException /
    // SecurityException (e.g. started without location permission). If we cannot
    // go foreground, stop self rather than crash the host process. // VERIFY.
    try {
      startForegroundCompat()
    } catch (e: Exception) {
      stopSelf()
      return START_NOT_STICKY
    }
    isRunning = true

    startLocationUpdates()
    requestActivityUpdates()
    registerProviderReceiver()

    return START_STICKY
  }

  override fun onDestroy() {
    isRunning = false
    runCatching { fused.removeLocationUpdates(locationCallback) }
    removeActivityUpdates()
    unregisterProviderReceiver()
    scope.cancel()
    super.onDestroy()
  }

  private fun registerProviderReceiver() {
    if (providerReceiver != null) return
    providerReceiver = runCatching {
      expo.modules.livetrack.ProviderReceiver.register(applicationContext)
    }.getOrNull()
  }

  private fun unregisterProviderReceiver() {
    providerReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
    providerReceiver = null
  }

  // --- Config -------------------------------------------------------------

  private fun readConfig(intent: Intent) {
    userId = intent.getStringExtra(EXTRA_USER_ID) ?: userId
    movingIntervalMs = intent.getLongExtra(EXTRA_MOVING_INTERVAL_MS, movingIntervalMs)
    movingDistanceM = intent.getFloatExtra(EXTRA_MOVING_DISTANCE_M, movingDistanceM)
    stillIntervalMs = intent.getLongExtra(EXTRA_STILL_INTERVAL_MS, stillIntervalMs)
    maxAccuracyM = intent.getDoubleExtra(EXTRA_MAX_ACCURACY_M, maxAccuracyM)
    // url/token are persisted in prefs by the module for the uploader; not needed here.
  }

  // --- Foreground notification -------------------------------------------

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      // IMPORTANCE_LOW: persistent, no sound, minimal intrusion.
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Location tracking",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Keeps location tracking active in the background."
        setShowBadge(false)
      }
      mgr.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    // Tapping opens the host app's launcher activity.
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val contentPi: PendingIntent? = launchIntent?.let {
      PendingIntent.getActivity(
        this,
        0,
        it,
        // FLAG_IMMUTABLE required on API 23+. // VERIFY: flag value.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Location tracking active")
      .setContentText("Recording your trip in the background.")
      // VERIFY: use a real small icon resource from the host app; android.R icon
      // used as a safe always-present placeholder to avoid a crash if no app icon.
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .apply { contentPi?.let { setContentIntent(it) } }
      .build()
  }

  private fun startForegroundCompat() {
    val notification = buildNotification()
    // ServiceCompat handles the Android 14+ typed-FGS requirement. // VERIFY: arg order.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceCompat.startForeground(
        this,
        NOTIF_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
      )
    } else {
      startForeground(NOTIF_ID, notification)
    }
  }

  // --- Location updates ---------------------------------------------------

  private fun buildLocationRequest(intervalMs: Long): LocationRequest {
    // VERIFY: LocationRequest.Builder API (play-services-location 21.x). Builder
    // takes (priority, intervalMillis); min update interval/distance are setters.
    return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
      .setMinUpdateIntervalMillis(intervalMs)
      .setMinUpdateDistanceMeters(movingDistanceM)
      .build()
  }

  private fun startLocationUpdates() {
    val intervalMs = if (isStill) stillIntervalMs else movingIntervalMs
    val request = buildLocationRequest(intervalMs)
    try {
      // Permission is the caller's responsibility (requested via the module);
      // wrapped in try/catch so a missing-permission SecurityException can't crash
      // the service. // VERIFY: requestLocationUpdates(request, callback, looper) signature.
      fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    } catch (e: SecurityException) {
      // No location permission — nothing to capture. Buffer/state will reflect it.
    }
  }

  private fun restartLocationUpdates() {
    runCatching { fused.removeLocationUpdates(locationCallback) }
    startLocationUpdates()
  }

  private fun handleLocation(loc: Location) {
    lastLat = loc.latitude
    lastLng = loc.longitude

    val battery = readBattery()
    val mock = isMock(loc)

    val point = PointEntity(
      userId = userId,
      t = System.currentTimeMillis(),
      lat = loc.latitude,
      lng = loc.longitude,
      // ALWAYS capture accuracy (fixes the production acc=null bug).
      acc = loc.accuracy.toDouble(),
      speed = loc.speed.toDouble(),
      batt = battery.first,
      charging = battery.second,
      act = currentActivity,
      mock = mock,
      eventType = null,
    )

    scope.launch { dao.insert(point) }

    // Nudge the uploader after buffering a fix (unique+KEEP, so cheap to repeat).
    expo.modules.livetrack.sync.UploadWorker.enqueue(applicationContext)

    // Best-effort UI emission (short-key shape from LiveTrack.types.ts).
    LiveTrackEventBus.emit(LiveTrackEventBus.EVENT_LOCATION, locationBundle(point))

    if (mock) {
      LiveTrackEventBus.emit(
        LiveTrackEventBus.EVENT_EVENT,
        eventBundle("MOCK_DETECTED", point.t, point.lat, point.lng, point.batt),
      )
    }
    // TODO next task: nudge the upload worker once buffer crosses batchSize.
  }

  private fun locationBundle(p: PointEntity): Bundle = Bundle().apply {
    putDouble("l", p.lat ?: 0.0)   // lat
    putDouble("g", p.lng ?: 0.0)   // lng
    putDouble("t", p.t.toDouble()) // ms epoch
    putDouble("s", p.speed)
    putDouble("acc", p.acc)
    putInt("b", p.batt)
    putBoolean("c", p.charging)
    putString("act", p.act ?: "UNKNOWN")
    putBoolean("mock", p.mock)
  }

  private fun eventBundle(
    type: String,
    t: Long,
    lat: Double?,
    lng: Double?,
    batt: Int,
  ): Bundle = Bundle().apply {
    putString("e", type)
    putDouble("t", t.toDouble())
    lat?.let { putDouble("l", it) }
    lng?.let { putDouble("g", it) }
    putInt("b", batt)
  }

  // --- Battery ------------------------------------------------------------

  /** @return (level 0..100 or -1, isCharging). */
  private fun readBattery(): Pair<Int, Boolean> {
    return try {
      val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
      val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
      // VERIFY: isCharging available on API 23+; falls back to false otherwise.
      val charging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        bm?.isCharging ?: false
      } else {
        false
      }
      level to charging
    } catch (e: Exception) {
      -1 to false
    }
  }

  private fun isMock(loc: Location): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        loc.isMock // API 31+
      } else {
        @Suppress("DEPRECATION")
        loc.isFromMockProvider // deprecated on 31+ but valid below.
      }
    } catch (e: Exception) {
      false
    }
  }

  // --- Activity recognition (motion-adaptive cadence) ---------------------
  // VERIFY (whole block): ActivityRecognition transition API against current
  // play-services-location. Wrapped in try/catch so any AR uncertainty/failure
  // CANNOT break the core FusedLocation capture path above.

  private fun activityTransitionRequest(): ActivityTransitionRequest {
    val transitions = listOf(
      transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
      transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
      transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
      transition(DetectedActivity.ON_FOOT, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
      transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
      transition(DetectedActivity.ON_BICYCLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
    )
    return ActivityTransitionRequest(transitions)
  }

  private fun transition(activity: Int, type: Int): ActivityTransition =
    ActivityTransition.Builder()
      .setActivityType(activity)
      .setActivityTransitionType(type)
      .build()

  private fun requestActivityUpdates() {
    try {
      val intent = Intent(this, TrackingService::class.java).apply {
        action = ACTION_ACTIVITY_TRANSITION
      }
      // getService delivers the transition result back into onStartCommand,
      // avoiding a BroadcastReceiver (which is a later task). // VERIFY: getService + flags.
      val pi = PendingIntent.getService(
        this,
        REQ_ACTIVITY,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
      )
      activityPendingIntent = pi
      // VERIFY: ActivityRecognition.getClient(this).requestActivityTransitionUpdates(request, pi).
      // Requires ACTIVITY_RECOGNITION runtime permission (API 29+); failure is swallowed.
      ActivityRecognition.getClient(this)
        .requestActivityTransitionUpdates(activityTransitionRequest(), pi)
    } catch (e: Exception) {
      // AR is an enhancement; capture still works at the moving cadence.
    }
  }

  private fun removeActivityUpdates() {
    val pi = activityPendingIntent ?: return
    try {
      // VERIFY: removeActivityTransitionUpdates(pendingIntent).
      ActivityRecognition.getClient(this).removeActivityTransitionUpdates(pi)
    } catch (e: Exception) {
      // ignore
    }
    activityPendingIntent = null
  }

  private fun handleActivityTransition(intent: Intent) {
    try {
      if (!ActivityTransitionResult.hasResult(intent)) return
      val result = ActivityTransitionResult.extractResult(intent) ?: return
      for (event in result.transitionEvents) {
        val entering = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        when (event.activityType) {
          DetectedActivity.STILL -> {
            if (entering) onEnterStill() else onExitStill()
          }
          else -> {
            if (entering) {
              currentActivity = activityName(event.activityType)
              if (isStill) onExitStill()
            }
          }
        }
      }
    } catch (e: Exception) {
      // ignore malformed transition payloads
    }
  }

  private fun onEnterStill() {
    if (isStill) return
    isStill = true
    currentActivity = "STILL"
    restartLocationUpdates() // switch to stillIntervalMs
    emitHeartbeat() // immediate heartbeat on becoming still
  }

  private fun onExitStill() {
    if (!isStill) return
    isStill = false
    restartLocationUpdates() // restore movingIntervalMs
  }

  /**
   * Insert a HEARTBEAT event row (with last known position) while still.
   *
   * NOTE: This is currently fired on the still-transition. A periodic
   * every-stillInterval heartbeat needs a timer/Handler or a self-scheduled
   * alarm; // TODO next task: drive periodic heartbeats while STILL (the
   * still-cadence FusedLocation callback also still fires and re-buffers).
   */
  private fun emitHeartbeat() {
    val now = System.currentTimeMillis()
    if (now - lastHeartbeatAt < stillIntervalMs && lastHeartbeatAt != 0L) return
    lastHeartbeatAt = now

    val row = PointEntity(
      userId = userId,
      t = now,
      lat = lastLat,
      lng = lastLng,
      act = "STILL",
      eventType = "HEARTBEAT",
    )
    scope.launch { dao.insert(row) }
    expo.modules.livetrack.sync.UploadWorker.enqueue(applicationContext)
    LiveTrackEventBus.emit(
      LiveTrackEventBus.EVENT_EVENT,
      eventBundle("HEARTBEAT", now, lastLat, lastLng, readBattery().first),
    )
  }

  private fun activityName(type: Int): String = when (type) {
    DetectedActivity.STILL -> "STILL"
    DetectedActivity.WALKING -> "WALKING"
    DetectedActivity.RUNNING -> "RUNNING"
    DetectedActivity.ON_FOOT -> "ON_FOOT"
    DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
    DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
    DetectedActivity.TILTING -> "TILTING"
    else -> "UNKNOWN"
  }

  companion object {
    const val CHANNEL_ID = "livetrack"
    const val NOTIF_ID = 4711
    private const val REQ_ACTIVITY = 1001

    const val ACTION_ACTIVITY_TRANSITION = "expo.modules.livetrack.ACTION_ACTIVITY_TRANSITION"

    const val EXTRA_USER_ID = "userId"
    const val EXTRA_URL = "url"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_MOVING_INTERVAL_MS = "movingIntervalMs"
    const val EXTRA_MOVING_DISTANCE_M = "movingDistanceM"
    const val EXTRA_STILL_INTERVAL_MS = "stillIntervalMs"
    const val EXTRA_MAX_ACCURACY_M = "maxAccuracyM"

    /** Whether the service is currently running. Read by getState(). */
    @Volatile
    var isRunning: Boolean = false
      private set
  }
}
