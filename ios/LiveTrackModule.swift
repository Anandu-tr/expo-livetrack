import ExpoModulesCore
import CoreLocation
import UIKit

/// Expo module bridge for LiveTrack (iOS).
///
/// Owns the JS-facing control surface (start/stop/getState/requestPermissions/...)
/// and relays best-effort capture/diagnostic events from `TrackingManager` and
/// `Uploader` to JS via `sendEvent`.
///
/// Module name "LiveTrack" must match `requireNativeModule('LiveTrack')` in JS, and
/// the event/function names must match the cross-platform contract (Android parity).
public class LiveTrackModule: Module {

  public func definition() -> ModuleDefinition {
    Name("LiveTrack")

    Events("onLocation", "onEvent", "onSyncError")

    OnCreate {
      // Wire the capture/upload layers' best-effort emitters back into JS. sendEvent
      // is dispatched to main by the callers; we hop to main here too for safety.
      // VERIFY: Expo `sendEvent(_:_:)` is safe to call from the main thread while JS
      // is attached; dropped silently otherwise (data is still buffered).
      TrackingManager.shared.emit = { [weak self] name, payload in
        DispatchQueue.main.async { self?.sendEvent(name, payload) }
      }
      Uploader.shared.onSyncError = { [weak self] message, status, bufferedCount in
        var payload: [String: Any] = [
          "message": message,
          "bufferedCount": bufferedCount,
        ]
        if let status = status { payload["status"] = status }
        DispatchQueue.main.async { self?.sendEvent("onSyncError", payload) }
      }
    }

    OnDestroy {
      TrackingManager.shared.emit = nil
      Uploader.shared.onSyncError = nil
    }

    // start(config): persist config + configure & start the TrackingManager.
    // VERIFY: Expo Modules accepts a loosely-typed `[String: Any]` argument here (and
    // `promise.resolve([String: Any])` below). If this expo-modules-core requires a
    // typed `Record` struct / `Convertible` conformance instead, switch start's param
    // and the getState/requestPermissions return values to a Record. Also covers the
    // Int64 values in sendEvent payloads ("t") crossing the JS bridge.
    Function("start") { (config: [String: Any]) in
      let url = config["url"] as? String ?? ""
      let token = config["token"] as? String ?? ""
      let userId = config["userId"] as? String ?? ""

      let cadence = config["cadence"] as? [String: Any] ?? [:]
      let movingIntervalMs = LiveTrackModule.num(cadence["movingIntervalMs"], 12_000)
      let movingDistanceM = LiveTrackModule.num(cadence["movingDistanceM"], 30)
      let stillIntervalMs = LiveTrackModule.num(cadence["stillIntervalMs"], 120_000)
      let maxAccuracyM = LiveTrackModule.num(cadence["maxAccuracyM"], 50)
      let batchSize = Int(LiveTrackModule.num(cadence["batchSize"], 50))

      let cfg = TrackingConfig(
        url: url,
        token: token,
        userId: userId,
        movingIntervalMs: movingIntervalMs,
        movingDistanceM: movingDistanceM,
        stillIntervalMs: stillIntervalMs,
        batchSize: batchSize,
        maxAccuracyM: maxAccuracyM
      )
      TrackingManager.shared.start(config: cfg)
    }

    // stop(): stop tracking + tear down timers.
    Function("stop") {
      TrackingManager.shared.stop()
    }

    // getState(): snapshot of tracking/permission/services/battery/buffer.
    AsyncFunction("getState") { (promise: Promise) in
      // Off-main: locationServicesEnabled() can emit a main-thread-blocking runtime
      // warning, and count() touches SQLite. // VERIFY: locationServicesEnabled off-main.
      DispatchQueue.global(qos: .userInitiated).async {
        let services = CLLocationManager.locationServicesEnabled()
        // Drive LOCATION_ON / LOCATION_OFF deltas from getState (mirrors Android's
        // getState-driven battery-opt delta). Buffers an event row + emits onEvent
        // only when the services flag changes between polls.
        TrackingManager.shared.notifyServicesEnabled(services)
        let permission = LiveTrackModule.permissionString()
        let bufferedCount = Buffer.shared.count()
        let state: [String: Any] = [
          "tracking": TrackingManager.shared.isTracking,
          "permission": permission,
          "locationServices": services,
          // iOS has no battery-optimization concept; always true (N/A).
          "batteryOptIgnored": true,
          "bufferedCount": bufferedCount,
        ]
        promise.resolve(state)
      }
    }

    // requestPermissions(): request Always authorization, then resolve with status.
    AsyncFunction("requestPermissions") { (promise: Promise) in
      // VERIFY: raw CLLocationManager vs Expo's permissions registry. We use raw CL to
      // mirror Android's "resolve our own TrackerState" approach. requestAlways must be
      // called on a manager whose delegate is set; TrackingManager owns one.
      DispatchQueue.main.async {
        TrackingManager.shared.requestAlwaysAuthorization()
        // The OS dialog result is async; resolve the *current* status. The host should
        // re-read getState() after the prompt settles (mirrors Android behaviour).
        DispatchQueue.global(qos: .userInitiated).async {
          let permission = LiveTrackModule.permissionString()
          let services = CLLocationManager.locationServicesEnabled()
          let bufferedCount = Buffer.shared.count()
          let state: [String: Any] = [
            "tracking": TrackingManager.shared.isTracking,
            "permission": permission,
            "locationServices": services,
            "batteryOptIgnored": true,
            "bufferedCount": bufferedCount,
          ]
          promise.resolve(state)
        }
      }
    }

    // requestEnableLocation(): iOS forbids programmatic toggling — deep-link to the
    // app's Settings page so the user can enable location. No package UI.
    AsyncFunction("requestEnableLocation") { (promise: Promise) in
      DispatchQueue.main.async {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
          promise.resolve(false)
          return
        }
        // VERIFY: UIApplication.shared.open completion handler runs on main.
        UIApplication.shared.open(url, options: [:]) { opened in
          promise.resolve(opened)
        }
      }
    }

    // ensureNotKilled(): no-op on iOS (no battery-optimization concept). Resolve now.
    AsyncFunction("ensureNotKilled") { (promise: Promise) in
      promise.resolve(nil)
    }
  }

  // MARK: - Helpers

  /// Maps CLAuthorizationStatus to the cross-platform TrackerState semantics:
  ///  - authorizedAlways    -> "granted"     (full background tracking)
  ///  - authorizedWhenInUse -> "background"  (needs upgrade to Always)
  ///  - denied/restricted/notDetermined -> "denied"
  private static func permissionString() -> String {
    // VERIFY: reading authorizationStatus via a transient CLLocationManager instance
    // off-main is fine (it's a cheap property); the deprecated static accessor differs
    // across iOS versions, so we use the instance property.
    let status: CLAuthorizationStatus
    if Thread.isMainThread {
      status = CLLocationManager().authorizationStatus
    } else {
      var s: CLAuthorizationStatus = .notDetermined
      DispatchQueue.main.sync { s = CLLocationManager().authorizationStatus }
      status = s
    }
    switch status {
    case .authorizedAlways:
      return "granted"
    case .authorizedWhenInUse:
      return "background"
    default:
      return "denied"
    }
  }

  /// Coerce a JS-bridged number (Int/Double/NSNumber/String) to Double with a default.
  private static func num(_ value: Any?, _ fallback: Double) -> Double {
    if let d = value as? Double { return d }
    if let i = value as? Int { return Double(i) }
    if let n = value as? NSNumber { return n.doubleValue }
    if let s = value as? String, let d = Double(s) { return d }
    return fallback
  }
}
