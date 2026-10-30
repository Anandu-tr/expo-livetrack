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
  /// ...or, for a spur reporting no motion, within this share of its shorter leg.
  static let zigzagReturnShare = 0.15
  /// Below this reported speed a fix claims to be stationary.
  static let stillSpeedMps = 0.5
  /// A fix reporting at least this speed proves motion.
  static let motionSpeedMps = 2.0
  /// How long proven motion vouches for later speed-less fixes.
  static let motionMemoryMs: Int64 = 60_000
  /// A stationary-claiming fix further than max(this, factor x acc) needs confirmation.
  static let unconfirmedMinJumpM = 75.0
  static let unconfirmedAccFactor = 3.0
  /// Parked points: one per this interval after the last written row.
  static let snapIntervalMs: Int64 = 600_000
  /// A parked fix within this of the last point is written as it is; a farther one
  /// (indoor fixes stray 20-30 m) is replaced by the fix that stayed nearest.
  static let parkedCloseM = 3.0
  /// Below this reported speed (on foot) a fix must stay within what its pace explains:
  /// paceFactor x pace x dt, plus both accuracies and paceMarginM, within paceCheckMaxDtS;
  /// paceFactorRiding from motionSpeedMps up (stop-and-go traffic, see the Android gate).
  static let walkingPaceMaxMps = 3.0
  static let paceFactor = 2.0
  static let paceFactorRiding = 3.0
  static let paceMarginM = 20.0
  static let paceCheckMaxDtS = 180.0
  /// Far fixes heading away together count as travel: at least travelMinFixes of them,
  /// gaining travelMinProgressM, never falling back more than travelNoiseM, each within
  /// travelBearingToleranceDeg of the newest one's bearing, inside travelWindowMs.
  static let travelMinFixes = 3
  static let travelMinProgressM = 150.0
  static let travelNoiseM = 25.0
  static let travelBearingToleranceDeg = 45.0
  static let travelWindowMs: Int64 = 900_000
  static let travelMaxFixes = 12
  /// ...the newest beyond the reach of indoor scatter (seen up to ~900 m).
  static let travelMinDistanceM = 1_200.0
  /// Far enough out for a trail to be worth an early probe.
  static let travelSuspectMinDistanceM = 500.0

  private static let earthRadiusM = 6_371_000.0

  static func haversineM(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
    let dLat = (lat2 - lat1) * .pi / 180
    let dLng = (lng2 - lng1) * .pi / 180
    let a = pow(sin(dLat / 2), 2) +
      cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * pow(sin(dLng / 2), 2)
    return 2 * earthRadiusM * asin(sqrt(a))
  }

  /// Initial bearing from the first point to the second, 0-360 degrees.
  static func bearingDeg(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
    let p1 = lat1 * .pi / 180
    let p2 = lat2 * .pi / 180
    let dLng = (lng2 - lng1) * .pi / 180
    let y = sin(dLng) * cos(p2)
    let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLng)
    return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
  }

  /// Smallest angle between two bearings, 0-180 degrees.
  static func angleBetweenDeg(_ a: Double, _ b: Double) -> Double {
    let d = abs(a - b).truncatingRemainder(dividingBy: 360)
    return d > 180 ? 360 - d : d
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
    /// Dropped. Reason is one of accuracy|stale|out-of-order|teleport|jitter|unconfirmed|inconsistent|zigzag.
    case reject(String)
    /// Accepted and held for the zig-zag lookahead; nothing to write yet.
    case hold(displacementM: Double)
    /// The previously held fix is released for writing; the new fix is now held.
    case release(fix: Fix, payload: T, displacementM: Double)
    /// Parked point: write a row at lat/lng with accuracy `acc`, stamped with the
    /// current fix's time `t`. The position is the current fix, or, when that strays
    /// more than `parkedCloseM` from the last written row, the fix seen since then
    /// that stayed nearest it; never that row's exact coordinates.
    case snap(lat: Double, lng: Double, t: Int64, acc: Double)
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

  /// A far fix on the candidate trail, with its distance and bearing from the anchor.
  private struct TrailFix {
    let fix: Fix
    let fromAnchorM: Double
    let bearingDeg: Double
  }

  /// The same far fixes, kept as a trail: a ride shows as fixes heading away together.
  private var trail: [TrailFix] = []
  /// Time of the last fix whose own speed proved motion.
  private var lastMotionT: Int64?
  /// The last row handed out for writing (release, keepalive or flush).
  private var lastOutLat: Double?
  private var lastOutLng: Double?
  private var lastOutT: Int64?
  /// Parked: the fix nearest the last written row since it was written, and its distance.
  private var parkedBest: Fix?
  private var parkedBestM = Double.greatestFiniteMagnitude

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

    if d < max(movingDistanceM, fix.acc) || isDrift(fix, anchor: a, d: d) {
      resetCandidate()
      if dt >= keepaliveMs {
        // Still here after a keepalive interval: refresh the time base even when
        // nothing is written, so the speed check stays strict while parked.
        anchorT = fix.t
        if let h = held {
          // The held real fix is the last position before the stop.
          held = nil
          emitted = h.fix
          let out = release(h, d: d)
          noteParked(fix)
          return out
        }
      }
      if held == nil {
        noteParked(fix)
        if isKeepaliveDue(fix) { return keepalive(now: fix) }
      }
      return .reject("jitter")
    }

    if isUnconfirmedJump(fix, d: d) {
      return confirmCandidate(fix) ? accept(fix: fix, payload: payload, d: d) : .reject("unconfirmed")
    }
    if outrunsOwnSpeed(fix, anchor: a) {
      return confirmCandidate(fix) ? accept(fix: fix, payload: payload, d: d) : .reject("inconsistent")
    }
    resetCandidate()

    return accept(fix: fix, payload: payload, d: d)
  }

  /// A big jump from a fix reporting no motion of its own, with no recent fix that
  /// did (parked-phone scatter). See the Android gate for the production case.
  private func isUnconfirmedJump(_ fix: Fix, d: Double) -> Bool {
    claimsStationary(fix) &&
      d > max(LocationGateLimits.unconfirmedMinJumpM, LocationGateLimits.unconfirmedAccFactor * fix.acc)
  }

  /// Stationary drift: a fix claiming no motion within the two fixes' combined
  /// accuracy of the anchor (indoor fixes wander 20-37 m without moving).
  private func isDrift(_ fix: Fix, anchor: Fix, d: Double) -> Bool {
    claimsStationary(fix) && d < fix.acc + anchor.acc
  }

  /// No motion of its own and no recent fix that proved motion.
  private func claimsStationary(_ fix: Fix) -> Bool {
    let recentMotion = lastMotionT.map { fix.t - $0 <= LocationGateLimits.motionMemoryMs } ?? false
    return reportsNoMotion(fix) && !recentMotion
  }

  private func reportsNoMotion(_ fix: Fix) -> Bool {
    (fix.speedMps ?? 0) < LocationGateLimits.stillSpeedMps
  }

  /// Parked points: one per snapIntervalMs, each the current fix when it stays within
  /// parkedCloseM of the previous point, otherwise the fix that stayed nearest it;
  /// never an exact repeat (see the Android gate).
  private func noteParked(_ fix: Fix) {
    guard let lat = lastOutLat, let lng = lastOutLng else { return }
    if fix.lat == lat && fix.lng == lng { return }
    let m = LocationGateLimits.haversineM(lat, lng, fix.lat, fix.lng)
    if m < parkedBestM {
      parkedBest = fix
      parkedBestM = m
    }
  }

  private func isKeepaliveDue(_ fix: Fix) -> Bool {
    parkedBest != nil && (lastOutT.map { fix.t - $0 >= LocationGateLimits.snapIntervalMs } ?? true)
  }

  private func keepalive(now: Fix) -> Outcome {
    guard let b = staysClose(now) ? now : parkedBest else { return .reject("jitter") }
    markOut(lat: b.lat, lng: b.lng, t: now.t)
    return .snap(lat: b.lat, lng: b.lng, t: now.t, acc: b.acc)
  }

  /// The current fix is close enough to the last point to be written as it is: always
  /// writing the nearest fix stripped the natural jitter the backend's spoofing check
  /// looks for (see the Android gate).
  private func staysClose(_ fix: Fix) -> Bool {
    guard let lat = lastOutLat, let lng = lastOutLng else { return false }
    if fix.lat == lat && fix.lng == lng { return false }
    return LocationGateLimits.haversineM(lat, lng, fix.lat, fix.lng) <= LocationGateLimits.parkedCloseM
  }

  private func markOut(lat: Double, lng: Double, t: Int64) {
    lastOutLat = lat
    lastOutLng = lng
    lastOutT = t
    parkedBest = nil
    parkedBestM = Double.greatestFiniteMagnitude
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
    noteTrail(fix)
    if cCount >= LocationGateLimits.reanchorCount || isTravel() {
      resetCandidate()
      return true
    }
    return false
  }

  private func resetCandidate() {
    cCount = 0
    trail.removeAll()
  }

  /// A ride: the trail heads away and is already farther out than indoor scatter
  /// reaches (seen drifting up to ~900 m along one bearing; see the Android gate).
  private func isTravel() -> Bool {
    guard let last = trail.last else { return false }
    return trail.count >= LocationGateLimits.travelMinFixes &&
      last.fromAnchorM >= LocationGateLimits.travelMinDistanceM &&
      headsAway(LocationGateLimits.travelMinProgressM)
  }

  private func noteTrail(_ fix: Fix) {
    guard let a = anchor else { return }
    trail.append(TrailFix(
      fix: fix,
      fromAnchorM: LocationGateLimits.haversineM(a.lat, a.lng, fix.lat, fix.lng),
      bearingDeg: LocationGateLimits.bearingDeg(a.lat, a.lng, fix.lat, fix.lng)
    ))
    while let first = trail.first,
      trail.count > LocationGateLimits.travelMaxFixes || fix.t - first.fix.t > LocationGateLimits.travelWindowMs {
      trail.removeFirst()
    }
  }

  /// The trail moves away from the anchor by at least `minProgressM`, every fix on
  /// the newest one's bearing, never falling back beyond noise, each step possible
  /// at the speed ceiling (see the Android gate for the production ride).
  private func headsAway(_ minProgressM: Double) -> Bool {
    guard let last = trail.last, let first = trail.first else { return false }
    if last.fromAnchorM - first.fromAnchorM < minProgressM { return false }
    for i in trail.indices {
      let p = trail[i]
      if LocationGateLimits.angleBetweenDeg(p.bearingDeg, last.bearingDeg) > LocationGateLimits.travelBearingToleranceDeg {
        return false
      }
      if i == 0 { continue }
      let q = trail[i - 1]
      if p.fromAnchorM < q.fromAnchorM - LocationGateLimits.travelNoiseM { return false }
      let dtS = max(Double(p.fix.t - q.fix.t) / 1000.0, 1.0)
      if LocationGateLimits.haversineM(q.fix.lat, q.fix.lng, p.fix.lat, p.fix.lng) >
        LocationGateLimits.maxSpeedMps * dtS + q.fix.acc + p.fix.acc {
        return false
      }
    }
    return true
  }

  /// A fix reporting walking pace that landed farther away than that pace explains;
  /// one the last written point explains is a return from a held spur (see Android).
  private func outrunsOwnSpeed(_ fix: Fix, anchor: Fix) -> Bool {
    if fix.speedMps == nil { return false }
    if paceExplains(fix, ref: anchor, refT: anchorT) { return false }
    if held != nil, let e = emitted, paceExplains(fix, ref: e, refT: e.t) { return false }
    return true
  }

  private func paceExplains(_ fix: Fix, ref: Fix, refT: Int64) -> Bool {
    let dtS = Double(fix.t - refT) / 1000.0
    if dtS <= 0 || dtS > LocationGateLimits.paceCheckMaxDtS { return true }
    let pace = max(fix.speedMps ?? 0, ref.speedMps ?? 0)
    if pace >= LocationGateLimits.walkingPaceMaxMps { return true }
    // Neither reports any speed at all (exactly 0), yet motion is vouched for: no pace
    // to judge by. A small measured speed is still a pace (see the Android gate).
    if pace == 0 && !claimsStationary(fix) { return true }
    let factor = pace >= LocationGateLimits.motionSpeedMps ? LocationGateLimits.paceFactorRiding : LocationGateLimits.paceFactor
    let d = LocationGateLimits.haversineM(ref.lat, ref.lng, fix.lat, fix.lng)
    return d <= max(
      LocationGateLimits.unconfirmedMinJumpM,
      factor * pace * dtS + fix.acc + ref.acc + LocationGateLimits.paceMarginM
    )
  }

  /// Release the held fix, if any, without waiting for a successor.
  func flush() -> (Fix, T)? {
    guard let h = held else { return nil }
    held = nil
    emitted = h.fix
    markOut(lat: h.fix.lat, lng: h.fix.lng, t: h.fix.t)
    return (h.fix, h.payload)
  }

  /// Far fixes rejected since the last one near the anchor seem to head away
  /// together: a ride rather than scatter (the Android service leaves still mode).
  func travelSuspected() -> Bool {
    guard let last = trail.last else { return false }
    return trail.count >= 2 && last.fromAnchorM >= LocationGateLimits.travelSuspectMinDistanceM &&
      headsAway(LocationGateLimits.travelNoiseM)
  }

  /// Motion reported from outside the fixes (a vehicle): speed-less fixes count as
  /// moving for motionMemoryMs after it. See the Android gate.
  func noteMotion(_ t: Int64) {
    if let last = lastMotionT, last >= t { return }
    lastMotionT = t
  }

  /// `flush()` for a still transition: a held fix far from the last written row
  /// that reports no motion may be a spur, so it stays held for the next fixes to
  /// judge (see the Android gate). iOS has no still mode today; kept for parity.
  func flushAtStill() -> (Fix, T)? {
    guard let h = held else { return nil }
    if let prev = emitted, reportsNoMotion(h.fix),
      LocationGateLimits.haversineM(prev.lat, prev.lng, h.fix.lat, h.fix.lng) > LocationGateLimits.zigzagLegM {
      return nil
    }
    return flush()
  }

  private func release(_ h: Held, d: Double) -> Outcome {
    markOut(lat: h.fix.lat, lng: h.fix.lng, t: h.fix.t)
    return .release(fix: h.fix, payload: h.payload, displacementM: d)
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
      return release(h, d: d)
    }
    return .hold(displacementM: d)
  }

  private func isZigZag(_ a: Fix, _ b: Fix, _ c: Fix) -> Bool {
    let ab = LocationGateLimits.haversineM(a.lat, a.lng, b.lat, b.lng)
    let bc = LocationGateLimits.haversineM(b.lat, b.lng, c.lat, c.lng)
    if ab <= LocationGateLimits.zigzagLegM || bc <= LocationGateLimits.zigzagLegM { return false }
    return LocationGateLimits.haversineM(a.lat, a.lng, c.lat, c.lng) < returnRadiusM(b, min(ab, bc))
  }

  /// How close C must come back to A for B to be a spur: a share of the spur's
  /// length when it reports no motion of its own, else the fixed radius (see Android).
  private func returnRadiusM(_ spur: Fix, _ shorterLegM: Double) -> Double {
    reportsNoMotion(spur)
      ? max(LocationGateLimits.zigzagReturnM, LocationGateLimits.zigzagReturnShare * shorterLegM)
      : LocationGateLimits.zigzagReturnM
  }
}
