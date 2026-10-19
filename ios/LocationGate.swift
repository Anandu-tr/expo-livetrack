import Foundation

/// Thresholds shared by every `LocationGate` instance. Kept outside the generic
/// class because Swift forbids static stored properties on generic types.
enum LocationGateLimits {
  /// Implied-speed ceiling. Field staff ride bikes and cars; 126 km/h is generous.
  static let maxSpeedMps = 35.0
  /// Fixes older than this (monotonic age) are cached replays, not the present.
  static let maxFixAgeMs = 60_000.0
  /// Floor for dt in the speed calculation (batched deliveries share timestamps).
  static let minDtMs: Int64 = 1_000
  /// Consecutive agreeing "teleport" fixes that move the anchor.
  static let reanchorCount = 3
  /// Agreement radius for the re-anchor candidate.
  static let reanchorRadiusM = 150.0
  /// A→B and B→C must both exceed this for B to be a zig-zag spur...
  static let zigzagLegM = 100.0
  /// ...while A→C stays within this.
  static let zigzagReturnM = 40.0
  /// Below this reported speed a fix claims to be stationary.
  static let stillSpeedMps = 0.5
  /// A fix reporting at least this speed proves motion.
  static let motionSpeedMps = 2.0
  /// How long proven motion vouches for later speed-less fixes.
  static let motionMemoryMs: Int64 = 60_000
  /// A stationary-claiming fix further than max(this, factor x acc) needs confirmation.
  static let unconfirmedMinJumpM = 75.0
  static let unconfirmedAccFactor = 3.0

  private static let earthRadiusM = 6_371_000.0

  static func haversineM(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
    let dLat = (lat2 - lat1) * .pi / 180
    let dLng = (lng2 - lng1) * .pi / 180
    let a = pow(sin(dLat / 2), 2) +
      cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * pow(sin(dLng / 2), 2)
    return 2 * earthRadiusM * asin(sqrt(a))
  }
}

/// Pure noise gate applied to every fix before it is buffered.
///
/// Mirrors `android/.../capture/LocationGate.kt` 1:1 — keep the two in sync.
/// Single-threaded by contract (CLLocationManager delivers on one thread).
///
/// Model: every accepted fix is HELD for one cycle so the next fix can vote on
/// whether it was a spur (zig-zag). The previously held fix is RELEASED when the
/// next one is accepted, so writes lag capture by one fix. `flush()` releases the
/// held fix on demand (stop).
final class LocationGate<T> {

  /// Minimal view of a fix; `acc < 0` means "no accuracy reported".
  /// `speedMps` is the fix's own reported speed, nil when it reports none.
  struct Fix {
    let lat: Double
    let lng: Double
    let acc: Double
    let t: Int64
    var speedMps: Double? = nil
  }

  enum Outcome {
    /// Dropped. Reason is one of accuracy|stale|out-of-order|teleport|jitter|unconfirmed|zigzag.
    case reject(String)
    /// Accepted and held for the zig-zag lookahead; nothing to write yet.
    case hold(displacementM: Double)
    /// The previously held fix is released for writing; the new fix is now held.
    case release(fix: Fix, payload: T, displacementM: Double)
    /// Stationary keepalive: write a synthetic row at the anchor with this time.
    case snap(lat: Double, lng: Double, t: Int64)
  }

  private struct Held {
    let fix: Fix
    let payload: T
  }

  private let movingDistanceM: Double
  private let maxAccuracyM: Double
  private let keepaliveMs: Int64

  private var emitted: Fix?
  private var held: Held?
  private var anchorT: Int64 = 0

  private var cLat = 0.0
  private var cLng = 0.0
  private var cT: Int64 = 0
  private var cCount = 0
  /// Time of the last fix whose own speed proved motion.
  private var lastMotionT: Int64?

  init(movingDistanceM: Double, maxAccuracyM: Double, keepaliveMs: Double) {
    self.movingDistanceM = movingDistanceM
    self.maxAccuracyM = maxAccuracyM
    self.keepaliveMs = Int64(keepaliveMs)
  }

  private var anchor: Fix? { held?.fix ?? emitted }

  func evaluate(fix: Fix, ageMs: Double, payload: T) -> Outcome {
    if fix.acc.isNaN || fix.acc < 0 || fix.acc > maxAccuracyM { return .reject("accuracy") }
    if ageMs > LocationGateLimits.maxFixAgeMs { return .reject("stale") }
    if (fix.speedMps ?? 0) >= LocationGateLimits.motionSpeedMps { lastMotionT = fix.t }

    guard let a = anchor else {
      held = Held(fix: fix, payload: payload)
      anchorT = fix.t
      return .hold(displacementM: 0)
    }

    let dt = fix.t - anchorT
    if dt <= 0 { return .reject("out-of-order") }

    let d = LocationGateLimits.haversineM(a.lat, a.lng, fix.lat, fix.lng)
    let speed = d / (Double(max(dt, LocationGateLimits.minDtMs)) / 1000.0)

    if speed > LocationGateLimits.maxSpeedMps {
      return confirmCandidate(fix) ? accept(fix: fix, payload: payload, d: d) : .reject("teleport")
    }

    if d < max(movingDistanceM, fix.acc) {
      cCount = 0
      if dt >= keepaliveMs {
        anchorT = fix.t
        if let h = held {
          held = nil
          emitted = h.fix
          return .release(fix: h.fix, payload: h.payload, displacementM: d)
        }
        return .snap(lat: a.lat, lng: a.lng, t: fix.t)
      }
      return .reject("jitter")
    }

    if isUnconfirmedJump(fix, d: d) {
      return confirmCandidate(fix) ? accept(fix: fix, payload: payload, d: d) : .reject("unconfirmed")
    }
    cCount = 0

    return accept(fix: fix, payload: payload, d: d)
  }

  /// A big jump from a fix reporting no motion of its own, with no recent fix that
  /// did (parked-phone scatter). See the Android gate for the production case.
  private func isUnconfirmedJump(_ fix: Fix, d: Double) -> Bool {
    let reportsNoMotion = (fix.speedMps ?? 0) < LocationGateLimits.stillSpeedMps
    let recentMotion = lastMotionT.map { fix.t - $0 <= LocationGateLimits.motionMemoryMs } ?? false
    return reportsNoMotion && !recentMotion &&
      d > max(LocationGateLimits.unconfirmedMinJumpM, LocationGateLimits.unconfirmedAccFactor * fix.acc)
  }

  /// Count `fix` towards the re-anchor candidate; true once enough agree.
  private func confirmCandidate(_ fix: Fix) -> Bool {
    if cCount > 0, fix.t > cT,
       LocationGateLimits.haversineM(cLat, cLng, fix.lat, fix.lng) <= LocationGateLimits.reanchorRadiusM {
      cCount += 1
    } else {
      cLat = fix.lat
      cLng = fix.lng
      cCount = 1
    }
    cT = fix.t
    if cCount >= LocationGateLimits.reanchorCount {
      cCount = 0
      return true
    }
    return false
  }

  /// Release the held fix, if any, without waiting for a successor.
  func flush() -> (Fix, T)? {
    guard let h = held else { return nil }
    held = nil
    emitted = h.fix
    return (h.fix, h.payload)
  }

  private func accept(fix: Fix, payload: T, d: Double) -> Outcome {
    let prev = emitted
    let h = held
    if let h = h, let prev = prev, isZigZag(prev, h.fix, fix) {
      held = nil
      anchorT = prev.t
      let back = LocationGateLimits.haversineM(prev.lat, prev.lng, fix.lat, fix.lng)
      if back < max(movingDistanceM, fix.acc) { return .reject("zigzag") }
      held = Held(fix: fix, payload: payload)
      anchorT = fix.t
      return .hold(displacementM: back)
    }

    anchorT = fix.t
    held = Held(fix: fix, payload: payload)
    if let h = h {
      emitted = h.fix
      return .release(fix: h.fix, payload: h.payload, displacementM: d)
    }
    return .hold(displacementM: d)
  }

  private func isZigZag(_ a: Fix, _ b: Fix, _ c: Fix) -> Bool {
    LocationGateLimits.haversineM(a.lat, a.lng, b.lat, b.lng) > LocationGateLimits.zigzagLegM &&
      LocationGateLimits.haversineM(b.lat, b.lng, c.lat, c.lng) > LocationGateLimits.zigzagLegM &&
      LocationGateLimits.haversineM(a.lat, a.lng, c.lat, c.lng) < LocationGateLimits.zigzagReturnM
  }
}
