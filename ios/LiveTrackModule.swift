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
    // AsyncFunction (like Android): the TS surface declares `Promise<void>`, and a
    // sync Function would surface native throws synchronously to `.catch` chains.
    AsyncFunction("start") { (config: [String: Any], promise: Promise) in
      let url = config["url"] as? String ?? ""
      let token = config["token"] as? String ?? ""
      let userId = config["userId"] as? String ?? ""

      let cadence = config["cadence"] as? [String: Any] ?? [:]
      let movingIntervalMs = LiveTrackModule.num(cadence["movingIntervalMs"], 12_000)
      let movingDistanceM = LiveTrackModule.num(cadence["movingDistanceM"], 30)
      let stillIntervalMs = LiveTrackModule.num(cadence["stillIntervalMs"], 120_000)
      let maxAccuracyM = LiveTrackModule.num(cadence["maxAccuracyM"], 50)
      // Clamped to the uploader's SQL-variable budget: deleteByIds/incrementAttempts
      // bind one variable per id and SQLITE_MAX_VARIABLE_NUMBER is 999 on older sqlite.
      let batchSize = min(max(Int(LiveTrackModule.num(cadence["batchSize"], 50)), 1), 500)
      let maxUploadAttempts = max(
        Int(LiveTrackModule.num(
          cadence["maxUploadAttempts"], Double(Uploader.defaultMaxUploadAttempts)
        )),
        1
      )

      let diagnostics = config["diagnostics"] as? [String: Any] ?? [:]
      let lifecycleEvents = diagnostics["lifecycleEvents"] as? Bool ?? false
      // 0 = archive off; clamped natively to 0..365 (rolling window, not a data lake).
      let archiveDays = min(max(Int(LiveTrackModule.num(config["archiveDays"], 0)), 0), 365)

      let cfg = TrackingConfig(
        url: url,
        token: token,
        userId: userId,
        movingIntervalMs: movingIntervalMs,
        movingDistanceM: movingDistanceM,
        stillIntervalMs: stillIntervalMs,
        batchSize: batchSize,
        maxAccuracyM: maxAccuracyM,
        maxUploadAttempts: maxUploadAttempts,
        lifecycleEvents: lifecycleEvents,
        archiveDays: archiveDays
      )
      TrackingManager.shared.start(config: cfg)
      promise.resolve(nil)
    }

    // stop(): stop tracking + tear down timers.
    AsyncFunction("stop") { (promise: Promise) in
      TrackingManager.shared.stop()
      promise.resolve(nil)
    }

    // replay(fromMs, toMs): re-send archived rows with their ORIGINAL ids/times.
    AsyncFunction("replay") { (fromMs: Double, toMs: Double, promise: Promise) in
      guard let cfg = TrackingConfig.load(), !cfg.url.isEmpty, !cfg.token.isEmpty, !cfg.userId.isEmpty else {
        promise.reject("ERR_REPLAY", "tracker not configured (call start first)")
        return
      }
      DispatchQueue.global(qos: .utility).async {
        let rows = Buffer.shared.archivedInRange(userId: cfg.userId, from: Int64(fromMs), to: Int64(toMs))
        Uploader.shared.replay(url: cfg.url, token: cfg.token, rows: rows, batchSize: cfg.batchSize) { result in
          switch result {
          case .success(let r):
            let out: [String: Any] = [
              "sent": r.sent, "accepted": r.accepted, "batches": r.batches, "failedBatches": r.failedBatches,
            ]
            promise.resolve(out)
          case .failure(let e):
            if let re = e as? Uploader.ReplayError {
              switch re {
              case .auth: promise.reject("ERR_REPLAY_AUTH", "ingest returned 401")
              case .busy: promise.reject("ERR_REPLAY_BUSY", "a replay is already in progress")
              }
            } else {
              promise.reject("ERR_REPLAY", e.localizedDescription)
            }
          }
        }
      }
    }

    // getCoverage(fromMs, toMs, gapMs): archive UNION still-unsynced buffer rows.
    AsyncFunction("getCoverage") { (fromMs: Double, toMs: Double, gapMs: Double, promise: Promise) in
      let userId = TrackingConfig.load()?.userId ?? ""
      DispatchQueue.global(qos: .userInitiated).async {
        let from = Int64(fromMs)
        let to = Int64(toMs)
        var pts: [CoverageCalculator.Pt] = []
        for r in Buffer.shared.archivedInRange(userId: userId, from: from, to: to) {
          if let la = r.lat, let lo = r.lng { pts.append(CoverageCalculator.Pt(t: r.t, lat: la, lng: lo)) }
        }
        for r in Buffer.shared.locationsInRange(userId: userId, from: from, to: to) {
          if let la = r.lat, let lo = r.lng { pts.append(CoverageCalculator.Pt(t: r.t, lat: la, lng: lo)) }
        }
        let gap = gapMs > 0 ? Int64(gapMs) : CoverageCalculator.defaultGapMs
        let r = CoverageCalculator.compute(pts, gapMs: gap)
        var out: [String: Any] = [
          "count": r.count,
          "gaps": r.gaps.map { g -> [String: Any] in
            ["fromT": Double(g.fromT), "toT": Double(g.toT), "durationMs": Double(g.durationMs), "distanceM": g.distanceM]
          },
        ]
        if let f = r.firstT { out["firstT"] = Double(f) } else { out["firstT"] = NSNull() }
        if let l = r.lastT { out["lastT"] = Double(l) } else { out["lastT"] = NSNull() }
        promise.resolve(out)
      }
    }

    // clearArchive(): privacy hook — the host calls this on sign-out.
    AsyncFunction("clearArchive") { (promise: Promise) in
      DispatchQueue.global(qos: .utility).async {
        Buffer.shared.clearArchive()
        promise.resolve(nil)
      }
    }

    // getArchiveStats(): row count, oldest timestamp, approximate on-disk size.
    AsyncFunction("getArchiveStats") { (promise: Promise) in
      DispatchQueue.global(qos: .utility).async {
        var out: [String: Any] = [
          "count": Buffer.shared.archiveCount(),
          "sizeBytes": Double(Buffer.shared.dbFileSizeBytes()),
        ]
        if let oldest = Buffer.shared.archiveOldestT() {
          out["oldestT"] = Double(oldest)
        } else {
          out["oldestT"] = NSNull()
        }
        promise.resolve(out)
      }
    }

    // exportArchive(sinceId, limit): archived rows newer than sinceId, oldest first,
    // for the TEMPORARY Supabase diagnostics upload (src/services/trackingLog.ts).
    // Reads archived_points ONLY — stable/non-racy, unlike `points`, which the
    // uploader deletes from the moment the backend ACKs a row.
    AsyncFunction("exportArchive") { (sinceId: Double, limit: Double, promise: Promise) in
      let userId = TrackingConfig.load()?.userId ?? ""
      DispatchQueue.global(qos: .utility).async {
        let rows = Buffer.shared.archivedAfterId(userId: userId, sinceId: Int64(sinceId), limit: Int(limit))
        let out: [String: Any] = [
          "rows": rows.map { r -> [String: Any] in
            [
              "l": r.lat ?? 0.0, "g": r.lng ?? 0.0, "t": Double(r.t), "s": r.speed, "acc": r.acc,
              "b": r.batt, "c": r.charging, "act": r.act ?? "UNKNOWN", "mock": r.mock,
            ]
          },
          "lastId": Double(rows.last?.id ?? Int64(sinceId)),
        ]
        promise.resolve(out)
      }
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
