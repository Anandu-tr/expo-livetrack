import Foundation
import CoreLocation
import UIKit

/// Tracking configuration (persisted to UserDefaults so a cold relaunch — e.g. via
/// significant-location-change wake — can reconstruct the manager).
struct TrackingConfig: Codable {
  let url: String
  let token: String
  let userId: String
  let movingIntervalMs: Double
  let movingDistanceM: Double
  let stillIntervalMs: Double
  let batchSize: Int
  let maxAccuracyM: Double

  /// MUST stay Optional. This struct is persisted to UserDefaults, and Swift's
  /// synthesized `Decodable` does NOT apply property defaults for missing keys — a
  /// non-optional new field would make `load()` return nil for every device that
  /// already has a config blob, silently killing the cold-relaunch re-arm path in
  /// `resumeIfNeeded()`. Read it through `effectiveMaxUploadAttempts`.
  let maxUploadAttempts: Int?

  /// Attempt budget with the back-compat fallback applied.
  var effectiveMaxUploadAttempts: Int {
    max(maxUploadAttempts ?? Uploader.defaultMaxUploadAttempts, 1)
  }

  private static let key = "expo.modules.livetrack.config"

  func save() {
    if let data = try? JSONEncoder().encode(self) {
      UserDefaults.standard.set(data, forKey: TrackingConfig.key)
    }
  }

  static func load() -> TrackingConfig? {
    guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
    return try? JSONDecoder().decode(TrackingConfig.self, from: data)
  }

  /// Whether the host last asked us to track (used by a cold-relaunch re-arm path).
  static var wasTracking: Bool {
    get { UserDefaults.standard.bool(forKey: "expo.modules.livetrack.wasTracking") }
    set { UserDefaults.standard.set(newValue, forKey: "expo.modules.livetrack.wasTracking") }
  }
}

/// Owns the `CLLocationManager`, captures fixes into the buffer, and drives uploads.
///
/// Resilience: in addition to standard location updates, we register for significant
/// location changes — iOS relaunches the app (into the background) after termination
/// when one fires. NOTE: an Expo module is NOT auto-instantiated on such a relaunch;
/// the host AppDelegate must observe `UIApplication.LaunchOptionsKey.location` and
/// call `TrackingManager.shared.resumeIfNeeded()` to recreate this manager from the
/// persisted config.
/// VERIFY / FOLLOW-UP: AppDelegate location-relaunch wiring is not part of this module
/// and must be added by the host app (or the config plugin) — see report.
final class TrackingManager: NSObject, CLLocationManagerDelegate {

  static let shared = TrackingManager()

  /// Best-effort emitter set by the module: (eventName, payload) -> sent to JS.
  var emit: ((String, [String: Any]) -> Void)?

  private var manager: CLLocationManager?
  private var config: TrackingConfig?
  private(set) var isTracking = false

  // Last known position, used to give HEARTBEAT rows context.
  private var lastLat: Double?
  private var lastLng: Double?
  private var lastMovedAt: TimeInterval = Date().timeIntervalSince1970
  private var heartbeatTimer: Timer?

  // Permission delta tracking (PERMISSION_GRANTED / PERMISSION_REVOKED) mirrors Android.
  private var lastAuthGranted: Bool?
  private var lastServicesEnabled: Bool?

  private override init() {
    super.init()
  }

  // MARK: - Lifecycle

  /// Start (or restart) tracking with a fresh config.
  func start(config: TrackingConfig) {
    self.config = config
    config.save()
    TrackingConfig.wasTracking = true
    ensureManager()
    configureAndStart()
  }

  /// Re-arm after a cold relaunch (called by the host AppDelegate on location wake).
  func resumeIfNeeded() {
    guard TrackingConfig.wasTracking, let cfg = TrackingConfig.load() else { return }
    self.config = cfg
    ensureManager()
    configureAndStart()
  }

  /// Request Always authorization (used by the module's requestPermissions).
  /// Ensures a manager exists first; must be called on the main thread.
  func requestAlwaysAuthorization() {
    ensureManager()
    if Thread.isMainThread {
      manager?.requestAlwaysAuthorization()
    } else {
      DispatchQueue.main.async { [weak self] in self?.manager?.requestAlwaysAuthorization() }
    }
  }

  func stop() {
    TrackingConfig.wasTracking = false
    isTracking = false
    heartbeatTimer?.invalidate()
    heartbeatTimer = nil
    manager?.stopUpdatingLocation()
    manager?.stopMonitoringSignificantLocationChanges()
  }

  // MARK: - Manager configuration

  private func ensureManager() {
    // CLLocationManager must be created and its delegate set on the main thread;
    // delegate callbacks then arrive on that thread.
    if Thread.isMainThread {
      createManagerIfNeeded()
    } else {
      DispatchQueue.main.sync { createManagerIfNeeded() }
    }
  }

  private func createManagerIfNeeded() {
    if manager == nil {
      let m = CLLocationManager()
      m.delegate = self
      manager = m
    }
  }

  private func configureAndStart() {
    let work = { [weak self] in
      guard let self = self, let m = self.manager, let cfg = self.config else { return }
      m.desiredAccuracy = kCLLocationAccuracyBest
      m.distanceFilter = cfg.movingDistanceM
      m.pausesLocationUpdatesAutomatically = false
      // allowsBackgroundLocationUpdates requires UIBackgroundModes:[location] in the
      // Info.plist (injected by the config plugin) AND an Always/WhenInUse grant.
      // VERIFY: setting this without the entitlement throws at runtime on some iOS
      // versions — it's guarded by the plugin-injected background mode.
      m.allowsBackgroundLocationUpdates = true
      // Always-authorization is what allows true background tracking.
      m.requestAlwaysAuthorization()
      m.startUpdatingLocation()
      // Significant-change monitoring relaunches the app after termination — the
      // resilience crux. // VERIFY: significantLocationChangeMonitoringAvailable().
      if CLLocationManager.significantLocationChangeMonitoringAvailable() {
        m.startMonitoringSignificantLocationChanges()
      }
      self.isTracking = true
      self.scheduleHeartbeatTimer()
    }
    if Thread.isMainThread { work() } else { DispatchQueue.main.async { work() } }
  }

  // MARK: - Heartbeat (stationary liveness)

  /// While stationary, insert a HEARTBEAT event row every ~stillIntervalMs so the
  /// backend can distinguish "stationary" from "dead". Kept simple: a repeating
  /// timer that only fires a heartbeat if we haven't moved within the interval.
  private func scheduleHeartbeatTimer() {
    heartbeatTimer?.invalidate()
    guard let cfg = config else { return }
    let interval = max(cfg.stillIntervalMs / 1000.0, 30)
    // VERIFY: Timer fires only while the run loop is alive; background execution of a
    // plain Timer is unreliable — significant-change wakes + per-fix capture are the
    // primary liveness signals. This heartbeat is best-effort foreground/short-bg.
    let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
      self?.maybeEmitHeartbeat(interval: interval)
    }
    RunLoop.main.add(timer, forMode: .common)
    heartbeatTimer = timer
  }

  private func maybeEmitHeartbeat(interval: TimeInterval) {
    let now = Date().timeIntervalSince1970
    // Only when stationary: no movement within the last interval.
    guard now - lastMovedAt >= interval else { return }
    let batt = TrackingManager.readBattery().level
    Buffer.shared.insertEvent(type: "HEARTBEAT", lastLat: lastLat, lastLng: lastLng, batt: batt)
    triggerSync()
    var payload: [String: Any] = [
      "e": "HEARTBEAT",
      "t": Int64(now * 1000),
      "b": batt,
    ]
    if let lat = lastLat { payload["l"] = lat }
    if let lng = lastLng { payload["g"] = lng }
    emitToJS("onEvent", payload)
  }

  // MARK: - Battery

  struct BatteryInfo { let level: Int; let charging: Bool }

  /// Reads battery level (0..100 or -1 sentinel) + charging state.
  /// `isBatteryMonitoringEnabled` MUST be true before reading `batteryLevel`.
  static func readBattery() -> BatteryInfo {
    let device = UIDevice.current
    if !device.isBatteryMonitoringEnabled {
      device.isBatteryMonitoringEnabled = true
    }
    let raw = device.batteryLevel // 0.0...1.0, or -1.0 if unknown
    let level = raw < 0 ? -1 : Int((raw * 100).rounded())
    // VERIFY: UIDevice.BatteryState.charging / .full enum cases.
    let state = device.batteryState
    let charging = (state == .charging || state == .full)
    return BatteryInfo(level: level, charging: charging)
  }

  // MARK: - Capture

  private func handleLocation(_ location: CLLocation) {
    guard let cfg = config else { return }

    // On-device accuracy filter — prevents unbounded buffer growth from points the
    // server would reject (drop acc < 0 [invalid] or acc > maxAccuracyM).
    let acc = location.horizontalAccuracy
    if acc < 0 || acc > cfg.maxAccuracyM {
      return // SKIP — do not buffer
    }

    let lat = location.coordinate.latitude
    let lng = location.coordinate.longitude

    // Movement bookkeeping for the heartbeat (distanceFilter already gates updates,
    // but track timing so a stationary device still triggers heartbeats).
    lastLat = lat
    lastLng = lng
    lastMovedAt = Date().timeIntervalSince1970

    let battery = TrackingManager.readBattery()
    let mock = TrackingManager.isMock(location)
    // Match Android: epoch milliseconds from the fix timestamp.
    let t = Int64(location.timestamp.timeIntervalSince1970 * 1000)
    let speed = max(0, location.speed) // CLLocation.speed is -1 when invalid

    Buffer.shared.insertLocation(
      userId: cfg.userId,
      t: t,
      lat: lat,
      lng: lng,
      speed: speed,
      acc: acc,
      batt: battery.level,
      charging: battery.charging,
      act: "UNKNOWN", // iOS has no cheap activity-type here (CMMotionActivity is separate)
      mock: mock
    )

    triggerSync()

    let payload: [String: Any] = [
      "l": lat,
      "g": lng,
      "t": t,
      "s": speed,
      "acc": acc,
      "b": battery.level,
      "c": battery.charging,
      "act": "UNKNOWN",
      "mock": mock,
    ]
    emitToJS("onLocation", payload)

    if mock {
      let ev: [String: Any] = ["e": "MOCK_DETECTED", "t": t, "l": lat, "g": lng, "b": battery.level]
      emitToJS("onEvent", ev)
    }
  }

  private static func isMock(_ location: CLLocation) -> Bool {
    // iOS 15+: CLLocation.sourceInformation?.isSimulatedBySoftware. Our deployment
    // floor is 16.4 so it's available, but guard for safety.
    // VERIFY: property name `sourceInformation.isSimulatedBySoftware` + availability.
    if #available(iOS 15.0, *) {
      return location.sourceInformation?.isSimulatedBySoftware ?? false
    }
    return false
  }

  // MARK: - Upload trigger

  private func triggerSync() {
    guard let cfg = config else { return }
    Uploader.shared.sync(
      url: cfg.url, token: cfg.token, batchSize: cfg.batchSize,
      maxAttempts: cfg.effectiveMaxUploadAttempts
    )
  }

  // MARK: - Events to JS (always on main)

  private func emitToJS(_ name: String, _ payload: [String: Any]) {
    if Thread.isMainThread {
      emit?(name, payload)
    } else {
      DispatchQueue.main.async { [weak self] in self?.emit?(name, payload) }
    }
  }

  /// Buffer a lifecycle event row + emit onEvent (best-effort).
  private func bufferLifecycleEvent(_ type: String) {
    let batt = TrackingManager.readBattery().level
    Buffer.shared.insertEvent(type: type, lastLat: lastLat, lastLng: lastLng, batt: batt)
    triggerSync()
    let payload: [String: Any] = [
      "e": type,
      "t": Int64(Date().timeIntervalSince1970 * 1000),
      "b": batt,
    ]
    emitToJS("onEvent", payload)
  }

  // MARK: - CLLocationManagerDelegate

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    for location in locations {
      handleLocation(location)
    }
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    // Best-effort: a transient location error (e.g. .locationUnknown) is non-fatal;
    // keep tracking. Hard failures surface via authorization/services callbacks.
    // VERIFY: CLError.Code.denied mapping if a deny arrives here on older iOS.
  }

  /// Authorization changes -> permission/services deltas (iOS 14+ delegate method).
  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    handleAuthOrServicesChange(manager: manager)
  }

  private func handleAuthOrServicesChange(manager: CLLocationManager) {
    let status = manager.authorizationStatus
    let granted = (status == .authorizedAlways || status == .authorizedWhenInUse)

    // Permission delta (only after we've seen a prior value).
    if let prev = lastAuthGranted, prev != granted {
      bufferLifecycleEvent(granted ? "PERMISSION_GRANTED" : "PERMISSION_REVOKED")
    }
    lastAuthGranted = granted

    // Auto-resume updates when (re)authorized.
    if granted, isTracking {
      manager.startUpdatingLocation()
    }
  }

  /// Called when location services are toggled on/off device-wide.
  /// Note: CLLocationManager has no direct "services changed" delegate; on iOS the
  /// authorization callback + a denied error stand in. We expose this so a host that
  /// observes services can drive LOCATION_ON / LOCATION_OFF.
  /// VERIFY: there is no first-class "locationServicesDidChange" delegate; LOCATION_OFF
  /// is best-detected from getState()/host polling. We buffer it here if invoked.
  func notifyServicesEnabled(_ enabled: Bool) {
    if let prev = lastServicesEnabled, prev != enabled {
      bufferLifecycleEvent(enabled ? "LOCATION_ON" : "LOCATION_OFF")
      if enabled, isTracking {
        // CLLocationManager calls must be on main (this may be invoked off-main from
        // the module's getState).
        DispatchQueue.main.async { [weak self] in self?.manager?.startUpdatingLocation() }
      }
    }
    lastServicesEnabled = enabled
  }
}
