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
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import expo.modules.livetrack.buffer.BufferDb
import expo.modules.livetrack.buffer.PointDao
import expo.modules.livetrack.buffer.PointEntity
import expo.modules.livetrack.capture.GateStats
import expo.modules.livetrack.capture.LocationGate
import expo.modules.livetrack.keepalive.KillStats
import expo.modules.livetrack.keepalive.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
  private val reporter: expo.modules.livetrack.diagnostics.DiagnosticsReporter by lazy {
    expo.modules.livetrack.diagnostics.DiagnosticsReporters.resolve(
      getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE),
    )
  }

  // Resolved from start-intent extras.
  private var userId: String = ""
  private var movingIntervalMs: Long = 12_000L
  private var movingDistanceM: Float = 30f
  private var stillIntervalMs: Long = 120_000L
  private var maxAccuracyM: Double = 50.0
  private var lifecycleEventsOn: Boolean = false

  // Noise gate; rebuilt on every (re)start so a fresh start() resets the anchor.
  private var gate: LocationGate<PointEntity>? = null

  // Motion state.
  @Volatile private var isStill: Boolean = false
  @Volatile private var currentActivity: String = "UNKNOWN"
  // Last ACCEPTED position, used to give HEARTBEAT / lifecycle rows context.
  @Volatile private var lastLat: Double? = null
  @Volatile private var lastLng: Double? = null
  private var lastHeartbeatAt: Long = 0L

  // Periodic proof-of-life while still (the location callback is quiet then).
  private val heartbeatHandler = Handler(Looper.getMainLooper())
  private val heartbeatTick = object : Runnable {
    override fun run() {
      if (!isStill || !isRunning) return
      emitHeartbeat()
      heartbeatHandler.postDelayed(this, stillIntervalMs)
    }
  }

  // Grace period before the cadence actually drops to stillIntervalMs (see
  // onEnterStill). Runs on the same main-thread Handler as everything else
  // here, so no synchronization is needed with isStill/onExitStill.
  private val graceHandler = Handler(Looper.getMainLooper())
  private val graceTick = Runnable {
    if (isStill && isRunning) restartLocationUpdates()
  }

  // Significant-motion is a one-shot hardware trigger sensor (near-zero power,
  // self-disarms after firing) that reports "device started moving" far faster
  // than Activity Recognition's own sampling and independently of the location
  // request's own interval. Armed only while still; a genuinely absent sensor
  // (getDefaultSensor returns null on some devices) just means this signal
  // never fires — AR sampling and GPS displacement remain the other two ways
  // still mode ends.
  private val sensorManager: SensorManager? by lazy {
    getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  }
  private val significantMotionListener = object : TriggerEventListener() {
    override fun onTrigger(event: TriggerEvent?) {
      // Dispatch through the main-thread handler regardless of the actual
      // delivery thread: requestTriggerSensor has no Handler-taking overload,
      // so its callback thread is whatever Looper was active at registration
      // time (the main thread here, since arming only ever happens from
      // onEnterStill on the main thread) — but every other piece of mutable
      // state in this class (isStill, currentActivity, lastLat/lastLng)
      // assumes single-threaded main-looper access with no synchronization,
      // so make that explicit rather than relying on an unenforced inference.
      graceHandler.post {
        // Self-disarmed by the OS already; the isStill check makes this a
        // no-op if we left still mode through another path in the meantime.
        if (isStill) {
          // Matches every other exit-still trigger (AR transition/sample,
          // GPS-displacement in noteAccepted()): clear the STILL label before
          // exiting so the next captured fix isn't mislabeled "STILL" while
          // actually moving again — speedActivity() takes over until AR corrects it.
          currentActivity = "UNKNOWN"
          onExitStill()
        }
      }
    }
  }

  private fun armSignificantMotion() {
    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
    runCatching { sensorManager?.requestTriggerSensor(significantMotionListener, sensor) }
  }

  private fun disarmSignificantMotion() {
    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
    runCatching { sensorManager?.cancelTriggerSensor(significantMotionListener, sensor) }
  }

  // --- Activity Recognition health -----------------------------------------
  // AR has been seen going silent for a day or more on POCO phones (no result
  // at all, so still mode never engages and the phone polls every 10 s all
  // night indoors). All times are SystemClock.elapsedRealtime().
  private var startedAt = 0L
  private var lastArResultAt = 0L
  private var lastArReregisterAt = 0L
  /** Start of the current run of fixes reporting speed < STILL_SPEED_MPS; 0 = none. */
  private var lowSpeedSince = 0L
  /** Last fix that reported real motion; vetoes an AR "STILL" right after it. */
  private var lastMovingFixAt = 0L
  /** What put us in still mode: AR, or the speed fallback while AR is silent. */
  private var stillSource = STILL_SOURCE_AR

  // --- Still-mode motion probes --------------------------------------------
  // In still mode the provider hands back network-grade fixes, which the gate
  // rejects, so a drive that AR misreads as STILL could go unseen for an hour.
  // A probe asks for one fresh high-accuracy fix; a moving one exits still mode
  // through the normal noteAccepted() path.
  private var probe: CancellationTokenSource? = null
  private var lastProbeAt = 0L
  private var nextProbeAt = 0L
  /** Consecutive probes that found the phone still; stretches the probe interval. */
  private var probeConfirms = 0

  // --- Periodic maintenance + STATS heartbeat ------------------------------
  private val stats = GateStats()
  private var lastStatsAt = 0L
  private val maintenanceHandler = Handler(Looper.getMainLooper())
  private val maintenanceTick = object : Runnable {
    override fun run() {
      if (!isRunning) return
      maintenance()
      maintenanceHandler.postDelayed(this, MAINTENANCE_TICK_MS)
    }
  }

  // Runtime-registered so PROVIDERS_CHANGED is reliably delivered while we're
  // alive (manifest delivery of this implicit broadcast is often suppressed).
  private var providerReceiver: expo.modules.livetrack.ProviderReceiver? = null

  private val locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      // Fused can deliver a small batch; its order is not guaranteed to be by
      // fix time, and the gate rejects anything older than the last accepted fix.
      for (loc in result.locations.sortedBy { it.time }) {
        handleLocation(loc)
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    fused = LocationServices.getFusedLocationProviderClient(this)
    createNotificationChannel()
    instance = this
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // AR results now go to ActivityReceiver; these only come from service
    // PendingIntents registered by an older build (replaced on next register).
    if (intent?.action == ACTION_ACTIVITY_TRANSITION || intent?.action == ACTION_ACTIVITY_SAMPLE) {
      if (isRunning) {
        onActivityIntent(intent)
        return START_STICKY
      }
      // The process died and this AR delivery re-created us bare. Previously that
      // left a service with no notification and no location for ~20 min.
      if (!getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.KEY_WAS_TRACKING, false)) {
        ActivityRegistrations.removeAll(applicationContext)
        stopSelf()
        return START_NOT_STICKY
      }
      return startTracking(null, "ar_restart")
    }

    // Normal start. A START_STICKY redelivery after process death carries a null
    // intent — config then comes from prefs, which is what `LiveTrackModule.start`
    // persisted (previously the defaults were used and rows went out with an
    // empty userId).
    val reason = if (intent == null) {
      "sticky_restart"
    } else {
      intent.getStringExtra(EXTRA_START_REASON) ?: "manual"
    }
    return startTracking(intent, reason)
  }

  /** Read config, go foreground and (re)arm capture. Shared by every start path. */
  private fun startTracking(intent: Intent?, reason: String): Int {
    if (intent != null && intent.hasExtra(EXTRA_USER_ID)) readConfig(intent) else readConfigFromPrefs()

    // Guard against Android 14 ForegroundServiceStartNotAllowedException /
    // SecurityException (e.g. started without location permission). If we cannot
    // go foreground, stop self rather than crash the host process. // VERIFY.
    try {
      startForegroundCompat()
    } catch (e: Exception) {
      // A background recovery can be refused; let the watchdog retry shortly.
      if (reason == "ar_restart") runCatching { Watchdog.scheduleSoon(applicationContext) }
      stopSelf()
      return START_NOT_STICKY
    }
    if (!isRunning) {
      val now = SystemClock.elapsedRealtime()
      startedAt = now
      lastStatsAt = now
      lastArResultAt = 0L
      lastArReregisterAt = 0L
      stats.reset()
    }
    isRunning = true

    startLocationUpdates()
    requestActivityUpdates()
    registerProviderReceiver()
    if (KillStats.isKillRecovery(reason)) recordKillRestart()
    bufferLifecycleEvent("SERVICE_STARTED", reason)
    maintenanceHandler.removeCallbacks(maintenanceTick)
    maintenanceHandler.postDelayed(maintenanceTick, MAINTENANCE_TICK_MS)

    return START_STICKY
  }

  private fun recordKillRestart() {
    val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    val updated = KillStats.record(prefs.getString(Prefs.KEY_KILL_RESTARTS, null), System.currentTimeMillis())
    prefs.edit().putString(Prefs.KEY_KILL_RESTARTS, updated).apply()
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Swipe-from-recents. The foreground service normally survives this; the
    // row makes it visible when it does not.
    bufferLifecycleEvent("SERVICE_STOPPED", "task_removed")
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    isRunning = false
    if (instance === this) instance = null
    heartbeatHandler.removeCallbacks(heartbeatTick)
    graceHandler.removeCallbacks(graceTick)
    maintenanceHandler.removeCallbacks(maintenanceTick)
    cancelProbe()
    disarmSignificantMotion()
    runCatching { fused.removeLocationUpdates(locationCallback) }
    // Keep AR registered if we're supposed to still be tracking: an unexpected
    // destroy (OEM/system) then gets recovered by the next AR result. After
    // stop() (wasTracking=false) it is removed so nothing wakes us.
    if (!getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.KEY_WAS_TRACKING, false)) {
      removeActivityUpdates()
    }
    unregisterProviderReceiver()
    // Blocking on purpose: the coroutine scope is cancelled right after, and the
    // held fix + stop marker must land in SQLite before the process goes away.
    flushGate(blocking = true)
    bufferLifecycleEvent("SERVICE_STOPPED", "destroy", blocking = true)
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
    onConfigLoaded()
  }

  private fun readConfigFromPrefs() {
    val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    userId = prefs.getString(Prefs.KEY_USER_ID, userId) ?: userId
    movingIntervalMs = prefs.getLong(Prefs.KEY_MOVING_INTERVAL_MS, movingIntervalMs)
    movingDistanceM = prefs.getFloat(Prefs.KEY_MOVING_DISTANCE_M, movingDistanceM)
    stillIntervalMs = prefs.getLong(Prefs.KEY_STILL_INTERVAL_MS, stillIntervalMs)
    maxAccuracyM = prefs.getFloat(Prefs.KEY_MAX_ACCURACY_M, maxAccuracyM.toFloat()).toDouble()
    onConfigLoaded()
  }

  private fun onConfigLoaded() {
    lifecycleEventsOn = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
      .getBoolean(Prefs.KEY_DIAGNOSTICS_LIFECYCLE, false)
    // A (re)start means a fresh anchor; release anything the old gate still held.
    flushGate()
    gate = newGate()
  }

  private fun newGate() = LocationGate<PointEntity>(
    movingDistanceM = movingDistanceM.toDouble(),
    maxAccuracyM = maxAccuracyM,
    keepaliveMs = stillIntervalMs,
  )

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

  private fun buildLocationRequest(intervalMs: Long, still: Boolean): LocationRequest {
    // VERIFY: LocationRequest.Builder API (play-services-location 21.x). Builder
    // takes (priority, intervalMillis); min update interval/distance are setters.
    //
    // Still mode keeps HIGH_ACCURACY (balanced power hands back Wi-Fi/cell fixes,
    // which are the jumpy ones) but drops the distance floor to 0: with the
    // floor in place a genuinely stationary device never received a fix, so the
    // still cadence never actually delivered anything and — worse — a device
    // whose Activity Recognition was stuck in STILL while riding produced gaps
    // of many minutes. The gate turns the 120 s trickle into keepalives, and
    // any real displacement in it exits still mode (see [noteAccepted]).
    return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
      .setMinUpdateIntervalMillis(intervalMs)
      .setMinUpdateDistanceMeters(if (still) 0f else movingDistanceM)
      .build()
  }

  private fun startLocationUpdates() {
    val intervalMs = if (isStill) stillIntervalMs else movingIntervalMs
    val request = buildLocationRequest(intervalMs, isStill)
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
    val g = gate ?: newGate().also { gate = it }

    // -1 = "no accuracy reported". The gate rejects it (matches the iOS `acc < 0`
    // rule); such fixes used to be stored as acc = 0.0, i.e. as if perfect. The
    // gate also enforces acc <= maxAccuracyM, which the server enforces too — a
    // buffered row the server refuses is never ACKed and re-sent forever.
    val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0
    // Monotonic age: a cached fix replayed after a restart looks current by
    // wall-clock but old by elapsedRealtime.
    val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
    val reportedSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else null
    val fix = LocationGate.Fix(
      loc.latitude, loc.longitude, acc, loc.time, loc.elapsedRealtimeNanos / 1_000_000L, reportedSpeed,
    )
    stats.delivered()
    noteReportedSpeed(acc, reportedSpeed, ageMs)

    val battery = readBattery()
    val mock = isMock(loc)

    // Prefer the Activity Recognition label when we actually have one; otherwise
    // (AR not yet reported, or unavailable/denied) derive a coarse label from GPS
    // speed so `act` isn't perpetually UNKNOWN.
    val act = if (currentActivity != "UNKNOWN") currentActivity else speedActivity(loc)

    // Metadata is captured at arrival and travels with the fix through the gate's
    // one-fix hold, so a row released later still describes its own moment.
    val point = PointEntity(
      userId = userId,
      // Fix time, not arrival time: batched deliveries share an arrival time and
      // produced near-identical `t` for consecutive points (seen as 100+ km/h
      // speed spikes on a smooth 35 km/h drive).
      t = loc.time,
      lat = loc.latitude,
      lng = loc.longitude,
      acc = acc,
      speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
      batt = battery.first,
      charging = battery.second,
      act = act,
      mock = mock,
      eventType = null,
    )

    when (val out = g.evaluate(fix, ageMs, point)) {
      is LocationGate.Outcome.Reject -> {
        Log.d(TAG, "gate reject=${out.reason} acc=$acc age=${ageMs}ms t=${loc.time}")
        stats.reject(out.reason)
        if (isStill && out.reason in PROBE_HINT_REASONS && isFarFromLastAccepted(loc, acc)) {
          maybeProbe(hint = true)
        }
      }
      is LocationGate.Outcome.Hold -> {
        stats.accepted()
        noteAccepted(loc, out.displacementM)
      }
      is LocationGate.Outcome.Release -> {
        stats.accepted()
        noteAccepted(loc, out.displacementM)
        writePoint(out.payload)
      }
      is LocationGate.Outcome.Snap -> {
        stats.snap()
        // Stationary keepalive at the anchor: proves liveness without painting
        // the jitter blob a parked device otherwise produces.
        writePoint(point.copy(t = out.t, lat = out.lat, lng = out.lng, speed = 0.0, act = "STILL"))
      }
    }
  }

  /**
   * Speed bookkeeping for usable fixes (current, accurate enough): feeds the
   * AR-silence still fallback and the veto on AR "STILL" while driving. Fixes
   * that report no speed say nothing either way.
   */
  private fun noteReportedSpeed(acc: Double, speed: Double?, ageMs: Long) {
    if (speed == null || acc < 0.0 || acc > maxAccuracyM || ageMs > LocationGate.MAX_FIX_AGE_MS) return
    val now = SystemClock.elapsedRealtime()
    if (speed >= LocationGate.STILL_SPEED_MPS) lowSpeedSince = 0L else if (lowSpeedSince == 0L) lowSpeedSince = now
    if (speed >= LocationGate.MOTION_SPEED_MPS) lastMovingFixAt = now
  }

  /** A rejected fix that still says "you've moved": far beyond its own accuracy. */
  private fun isFarFromLastAccepted(loc: Location, acc: Double): Boolean {
    val lat = lastLat ?: return false
    val lng = lastLng ?: return false
    val d = LocationGate.haversineM(lat, lng, loc.latitude, loc.longitude)
    return d > acc.coerceAtLeast(0.0) + PROBE_HINT_MARGIN_M
  }

  /** Bookkeeping for every fix the gate accepted (held or released). */
  private fun noteAccepted(loc: Location, displacementM: Double) {
    lastLat = loc.latitude
    lastLng = loc.longitude
    // Activity Recognition can sit in STILL for an entire ride (observed: 120 s
    // cadence over 9 km, with gaps of up to an hour). Real displacement is the
    // ground truth, so leave still mode from here instead of waiting for AR.
    if (isStill &&
      (displacementM > movingDistanceM * STILL_EXIT_DISTANCE_FACTOR ||
        (loc.hasSpeed() && loc.speed > STILL_EXIT_SPEED_MPS))
    ) {
      currentActivity = speedActivity(loc).takeIf { it != "STILL" } ?: "UNKNOWN"
      onExitStill()
    }
  }

  private fun writePoint(point: PointEntity) {
    scope.launch {
      runCatching { dao.insert(point) }.onFailure {
        reporter.recordFailure("db-write", it.message ?: "insert(location) failed", mapOf("hasLocation" to "true"))
      }
    }

    // Nudge the uploader after buffering a fix (unique+KEEP, so cheap to repeat).
    expo.modules.livetrack.sync.UploadWorker.enqueue(applicationContext)

    // Best-effort UI emission (short-key shape from LiveTrack.types.ts).
    LiveTrackEventBus.emit(LiveTrackEventBus.EVENT_LOCATION, locationBundle(point))

    if (point.mock) {
      LiveTrackEventBus.emit(
        LiveTrackEventBus.EVENT_EVENT,
        eventBundle("MOCK_DETECTED", point.t, point.lat, point.lng, point.batt),
      )
    }
  }

  /**
   * Release the fix the gate is holding for its lookahead. Called on the still
   * transition (so the last real position is written before the keepalives
   * start) and on destroy, where the insert must complete synchronously.
   */
  private fun flushGate(blocking: Boolean = false) {
    val (_, payload) = gate?.flush() ?: return
    if (blocking) {
      runCatching { runBlocking(Dispatchers.IO) { dao.insert(payload) } }
    } else {
      writePoint(payload)
    }
  }

  /**
   * SERVICE_STARTED / SERVICE_STOPPED rows, opt-in via `diagnostics.lifecycleEvents`.
   * `reason` (manual, boot, package_replaced, watchdog, sticky_restart, receiver,
   * task_removed, destroy) rides in the `act` column and is serialised as `r`.
   */
  private fun bufferLifecycleEvent(type: String, reason: String, blocking: Boolean = false) {
    if (!lifecycleEventsOn) return
    val now = System.currentTimeMillis()
    val batt = readBattery().first
    val row = PointEntity(
      userId = userId,
      t = now,
      lat = lastLat,
      lng = lastLng,
      batt = batt,
      act = reason,
      eventType = type,
    )
    if (blocking) {
      runCatching { runBlocking(Dispatchers.IO) { dao.insert(row) } }
    } else {
      scope.launch {
        runCatching { dao.insert(row) }.onFailure {
          reporter.recordFailure("db-write", it.message ?: "insert(event) failed", mapOf("hasLocation" to "false"))
        }
      }
    }
    runCatching { expo.modules.livetrack.sync.UploadWorker.enqueue(applicationContext) }
    LiveTrackEventBus.emit(
      LiveTrackEventBus.EVENT_EVENT,
      eventBundle(type, now, lastLat, lastLng, batt).apply { putString("r", reason) },
    )
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
      .setActivityTransition(type)
      .build()

  // (a) Transitions drive responsive still<->moving cadence switching.
  // (b) Sampling periodically reports the CURRENT most-probable activity. This
  // seeds the activity at startup (transitions alone never report a current
  // state) and continuously corrects stale labels (e.g. stuck WALKING).
  private fun requestActivityUpdates() {
    try {
      ActivityRegistrations.request(this, activityTransitionRequest())
    } catch (e: Exception) {
      // AR is an enhancement; capture still works at the moving cadence and the
      // GPS-speed fallback supplies an activity label. Surface the cause (most
      // often a denied ACTIVITY_RECOGNITION permission or missing Play Services).
      android.util.Log.w(TAG, "Activity Recognition unavailable; using speed fallback", e)
    }
  }

  private fun removeActivityUpdates() {
    ActivityRegistrations.removeAll(this)
  }

  /** An AR result, from [ActivityReceiver] or a legacy service PendingIntent. Main thread. */
  fun onActivityIntent(intent: Intent) {
    when (intent.action) {
      ACTION_ACTIVITY_TRANSITION -> handleActivityTransition(intent)
      ACTION_ACTIVITY_SAMPLE -> handleActivitySample(intent)
    }
  }

  /**
   * AR misreads smooth driving as STILL; a fix that just reported real speed
   * outranks it. The grace period would exit again anyway, but only after
   * churning the location request and painting a heartbeat mid-drive.
   */
  private fun recentlyMoving(): Boolean =
    lastMovingFixAt != 0L && SystemClock.elapsedRealtime() - lastMovingFixAt < AR_STILL_VETO_MS

  private fun handleActivityTransition(intent: Intent) {
    try {
      if (!ActivityTransitionResult.hasResult(intent)) return
      val result = ActivityTransitionResult.extractResult(intent) ?: return
      lastArResultAt = SystemClock.elapsedRealtime()
      for (event in result.transitionEvents) {
        val entering = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        when (event.activityType) {
          DetectedActivity.STILL -> {
            if (!entering) onExitStill() else if (!recentlyMoving()) onEnterStill(STILL_SOURCE_AR)
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

  // Periodic current-activity sample. Unlike transitions, this reports the
  // most-probable activity RIGHT NOW, so it seeds the label at startup and
  // continuously corrects stale values. Reconciled through the same helpers as
  // transitions to keep isStill / currentActivity / cadence consistent.
  private fun handleActivitySample(intent: Intent) {
    try {
      if (!ActivityRecognitionResult.hasResult(intent)) return
      val result = ActivityRecognitionResult.extractResult(intent) ?: return
      // Any result, even a low-confidence one, proves AR is delivering.
      lastArResultAt = SystemClock.elapsedRealtime()
      val mostProbable = result.mostProbableActivity
      if (mostProbable.confidence < MIN_CONFIDENCE) return
      when (mostProbable.type) {
        DetectedActivity.STILL -> if (!recentlyMoving()) onEnterStill(STILL_SOURCE_AR)
        DetectedActivity.UNKNOWN, DetectedActivity.TILTING -> {
          // Not actionable: don't overwrite a known label with UNKNOWN/TILTING.
        }
        else -> {
          currentActivity = activityName(mostProbable.type)
          if (isStill) onExitStill()
        }
      }
    } catch (e: Exception) {
      // ignore malformed sample payloads
    }
  }

  /** @param source [STILL_SOURCE_AR], or [STILL_SOURCE_NOAR] from the speed fallback. */
  private fun onEnterStill(source: String) {
    if (isStill) {
      // AR confirming a fallback-entered still state: report it as AR from now on.
      if (source == STILL_SOURCE_AR) stillSource = STILL_SOURCE_AR
      return
    }
    isStill = true
    stillSource = source
    currentActivity = "STILL"
    probeConfirms = 0
    nextProbeAt = SystemClock.elapsedRealtime() + PROBE_FIRST_DELAY_MS
    // Write the fix the gate is holding first: it is the last real position
    // before the stationary period, and nothing else would release it in time.
    flushGate()
    // Grace period: keep the current (moving) cadence running for
    // STILL_GRACE_MS instead of switching to stillIntervalMs immediately, so a
    // stop-then-immediately-go-again is fully covered at full resolution and
    // never loses a beat. Only if genuinely still-still after the grace
    // window does graceTick actually throttle the cadence down.
    graceHandler.removeCallbacks(graceTick)
    graceHandler.postDelayed(graceTick, STILL_GRACE_MS)
    armSignificantMotion() // near-instant, near-zero-power backup exit trigger
    emitHeartbeat() // immediate heartbeat on becoming still
    heartbeatHandler.removeCallbacks(heartbeatTick)
    heartbeatHandler.postDelayed(heartbeatTick, stillIntervalMs)
  }

  private fun onExitStill() {
    if (!isStill) return
    isStill = false
    lowSpeedSince = 0L
    nextProbeAt = 0L
    probeConfirms = 0
    cancelProbe()
    graceHandler.removeCallbacks(graceTick)
    disarmSignificantMotion()
    heartbeatHandler.removeCallbacks(heartbeatTick)
    restartLocationUpdates() // restore movingIntervalMs
  }

  /**
   * Insert a HEARTBEAT event row (with last accepted position) while still.
   *
   * Fired on the still-transition and then every `stillIntervalMs` by
   * [heartbeatTick] for as long as the device stays still, so a long stationary
   * period is distinguishable from a dead service. `r` is [stillSource], so a
   * still state entered without Activity Recognition shows as `STILL_NOAR`.
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
      act = stillSource,
      eventType = "HEARTBEAT",
    )
    scope.launch {
      runCatching { dao.insert(row) }.onFailure {
        reporter.recordFailure("db-write", it.message ?: "insert(event) failed", mapOf("hasLocation" to "false"))
      }
    }
    expo.modules.livetrack.sync.UploadWorker.enqueue(applicationContext)
    LiveTrackEventBus.emit(
      LiveTrackEventBus.EVENT_EVENT,
      eventBundle("HEARTBEAT", now, lastLat, lastLng, readBattery().first),
    )
  }

  /**
   * Once a minute while running (main looper; stretched by deep sleep, which is
   * fine because every location delivery wakes us too): Activity Recognition
   * health, the still-mode probe schedule and the STATS heartbeat.
   */
  private fun maintenance() {
    val now = SystemClock.elapsedRealtime()
    val arSilent = now - maxOf(lastArResultAt, startedAt) > AR_SILENCE_MS
    if (arSilent && now - lastArReregisterAt > AR_REREGISTER_INTERVAL_MS) {
      // Re-requesting with the same PendingIntents replaces the registration:
      // harmless if AR is merely quiet, recovers it if it was dropped.
      lastArReregisterAt = now
      requestActivityUpdates()
    }
    // Without AR, nothing else would ever enter still mode: a parked phone keeps
    // polling every movingIntervalMs indoors, which is where the scatter comes from.
    if (arSilent && !isStill && lowSpeedSince != 0L && now - lowSpeedSince >= STILL_BY_SPEED_MS) {
      onEnterStill(STILL_SOURCE_NOAR)
    }
    if (isStill) maybeProbe(hint = false)
    if (lifecycleEventsOn && now - lastStatsAt >= STATS_INTERVAL_MS) emitStats(now)
  }

  /**
   * Start a probe if one is due. Scheduled probes back off while they keep
   * finding the phone still; hint probes (a rejected fix far from the last
   * position) come sooner but with a gap that grows the same way, so a parked
   * phone's scatter can't keep GNSS busy.
   */
  private fun maybeProbe(hint: Boolean) {
    if (!isStill || !isRunning || probe != null) return
    val now = SystemClock.elapsedRealtime()
    val due = if (hint) {
      now - lastProbeAt >= minOf(PROBE_HINT_MIN_GAP_MS shl probeConfirms.coerceAtMost(8), PROBE_MAX_INTERVAL_MS)
    } else {
      nextProbeAt != 0L && now >= nextProbeAt
    }
    if (due) startProbe(now)
  }

  private fun startProbe(now: Long) {
    val source = CancellationTokenSource()
    probe = source
    lastProbeAt = now
    stats.probe()
    val request = CurrentLocationRequest.Builder()
      .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
      .setMaxUpdateAgeMillis(0) // a fresh fix, never the provider's cached one
      .setDurationMillis(PROBE_DURATION_MS)
      .build()
    try {
      fused.getCurrentLocation(request, source.token).addOnCompleteListener { task ->
        if (probe !== source) return@addOnCompleteListener // cancelled on exit/destroy
        probe = null
        val loc = if (task.isSuccessful) task.result else null
        // Same gate as every other fix: a moving one exits via noteAccepted().
        if (loc != null && isRunning) handleLocation(loc)
        if (isStill) {
          probeConfirms++
          nextProbeAt = SystemClock.elapsedRealtime() +
            minOf(PROBE_FIRST_DELAY_MS shl probeConfirms.coerceAtMost(8), PROBE_MAX_INTERVAL_MS)
        }
      }
    } catch (e: SecurityException) {
      probe = null
    }
  }

  private fun cancelProbe() {
    probe?.cancel()
    probe = null
  }

  /**
   * HEARTBEAT row whose `r` carries [GateStats] for the last window, in any
   * mode, so gaps can be classified server-side (nothing delivered vs rejected).
   */
  private fun emitStats(now: Long) {
    lastStatsAt = now
    val arAgeMin = if (lastArResultAt == 0L) null else (now - lastArResultAt) / 60_000L
    val row = PointEntity(
      userId = userId,
      t = System.currentTimeMillis(),
      lat = lastLat,
      lng = lastLng,
      batt = readBattery().first,
      act = stats.format(isStill, arAgeMin),
      eventType = "HEARTBEAT",
    )
    stats.reset()
    scope.launch {
      runCatching { dao.insert(row) }.onFailure {
        reporter.recordFailure("db-write", it.message ?: "insert(stats) failed", mapOf("hasLocation" to "false"))
      }
    }
    runCatching { expo.modules.livetrack.sync.UploadWorker.enqueue(applicationContext) }
  }

  // Coarse GPS-speed-derived activity, used only as a fallback when Activity
  // Recognition has given no usable label. Thresholds are conservative and
  // tunable; GPS speed can jitter slightly when truly stationary. Returns
  // "UNKNOWN" when the fix carries no speed (avoids a false "STILL").
  private fun speedActivity(loc: Location): String {
    if (!loc.hasSpeed()) return "UNKNOWN"
    return when {
      loc.speed < 0.5f -> "STILL" // ~<1.8 km/h
      loc.speed < 2.5f -> "WALKING" // ~<9 km/h
      else -> "IN_VEHICLE"
    }
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
    private const val TAG = "LiveTrack"
    const val CHANNEL_ID = "livetrack"
    const val NOTIF_ID = 4711

    // Minimum confidence (0-100) before we trust a sampled activity.
    private const val MIN_CONFIDENCE = 50

    // How long to keep the fast (moving) cadence running after entering STILL
    // before actually throttling down to stillIntervalMs. Covers a stop that
    // immediately starts moving again at full resolution, at the cost of
    // spending the first bit of every stop — even a long one — at the
    // higher-power cadence.
    private const val STILL_GRACE_MS = 90_000L

    // A still-mode fix that moved this many `movingDistanceM`s from the anchor,
    // or that reports at least this speed, ends still mode without waiting for AR.
    private const val STILL_EXIT_DISTANCE_FACTOR = 2.0
    private const val STILL_EXIT_SPEED_MPS = 1.0f

    // Heartbeat `r` values: who decided we're still.
    private const val STILL_SOURCE_AR = "STILL"
    private const val STILL_SOURCE_NOAR = "STILL_NOAR"

    // AR samples are requested every 10 s; 15 min without a single result means
    // it has stopped delivering (seen for 1-2 days at a time on POCO phones).
    private const val AR_SILENCE_MS = 15 * 60_000L
    private const val AR_REREGISTER_INTERVAL_MS = 30 * 60_000L
    // With AR silent, this long of fixes reporting ~0 speed counts as still.
    private const val STILL_BY_SPEED_MS = 3 * 60_000L
    // An AR "STILL" this soon after a fix reported real speed is ignored.
    private const val AR_STILL_VETO_MS = 30_000L

    // Still-mode probes: first one 4 min in, doubling while the phone stays
    // still, capped at 10 min (the longest a misread drive can go unseen).
    // Each probe keeps GNSS on for at most 30 s: <= ~5% duty while parked.
    private const val PROBE_FIRST_DELAY_MS = 4 * 60_000L
    private const val PROBE_MAX_INTERVAL_MS = 10 * 60_000L
    private const val PROBE_DURATION_MS = 30_000L
    // Hint probes: at most every 2 min, stretched like the schedule.
    private const val PROBE_HINT_MIN_GAP_MS = 2 * 60_000L
    // A rejected fix this far beyond its own accuracy from the last position.
    private const val PROBE_HINT_MARGIN_M = 150.0
    private val PROBE_HINT_REASONS = setOf("accuracy", "teleport", "unconfirmed")

    private const val MAINTENANCE_TICK_MS = 60_000L
    private const val STATS_INTERVAL_MS = 10 * 60_000L

    const val ACTION_ACTIVITY_TRANSITION = "expo.modules.livetrack.ACTION_ACTIVITY_TRANSITION"
    const val ACTION_ACTIVITY_SAMPLE = "expo.modules.livetrack.ACTION_ACTIVITY_SAMPLE"

    /** Why the service was started; surfaces as the SERVICE_STARTED event's `r`. */
    const val EXTRA_START_REASON = "startReason"
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

    /** The live instance, for [ActivityReceiver] to hand AR results to. Main thread. */
    @Volatile
    var instance: TrackingService? = null
      private set
  }
}
