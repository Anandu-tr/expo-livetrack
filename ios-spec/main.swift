// Executable spec for ios/LocationGate.swift, mirroring the Android
// LocationGateTest cases that apply to the iOS gate (iOS has wall-clock time
// only, so the monotonic-clock cases are not ported).
// Kept outside ios/ (the podspec compiles every Swift file there). Run: see
// "Native unit tests" in the README.
import Foundation

var failures = 0
var passed = 0
var current = ""

func check(_ cond: Bool, _ msg: @autoclosure () -> String, line: Int = #line) {
  if !cond {
    failures += 1
    print("  FAIL \(current) (line \(line)): \(msg())")
  }
}

func test(_ name: String, _ body: () -> Void) {
  current = name
  let before = failures
  body()
  if failures == before { passed += 1 }
}

typealias G = LocationGate<String>
let lat0 = 9.98
let lng0 = 76.38
let t0: Int64 = 1_788_000_000_000

func gate() -> G { G(movingDistanceM: 20, maxAccuracyM: 35, keepaliveMs: 120_000) }

func fix(_ dLatM: Double, _ dtS: Double, acc: Double = 10, speed: Double? = 5.0, dLngM: Double = 0) -> G.Fix {
  G.Fix(
    lat: lat0 + dLatM / 111_000.0,
    lng: lng0 + dLngM / (111_000.0 * cos(lat0 * .pi / 180)),
    acc: acc,
    t: t0 + Int64(dtS * 1000),
    speedMps: speed
  )
}

extension LocationGate where T == String {
  func eval(_ f: Fix, _ p: String, ageMs: Double = 0) -> Outcome { evaluate(fix: f, ageMs: ageMs, payload: p) }
}

func reject(_ o: G.Outcome) -> String? { if case .reject(let r) = o { return r }; return nil }
func holdD(_ o: G.Outcome) -> Double? { if case .hold(let d) = o { return d }; return nil }
func released(_ o: G.Outcome) -> (payload: String, d: Double)? {
  if case .release(_, let p, let d) = o { return (p, d) }; return nil
}
func snap(_ o: G.Outcome) -> (lat: Double, lng: Double, t: Int64, acc: Double)? {
  if case .snap(let la, let ln, let t, let a) = o { return (la, ln, t, a) }; return nil
}

// --- accuracy / staleness / order ---------------------------------------------

test("firstFixIsHeldNotWritten") { check(holdD(gate().eval(fix(0, 0), "p1")) != nil, "not held") }
test("noAccuracyIsRejected") { check(reject(gate().eval(fix(0, 0, acc: -1), "p1")) == "accuracy", "") }
test("poorAccuracyIsRejected") { check(reject(gate().eval(fix(0, 0, acc: 36), "p1")) == "accuracy", "") }
test("staleFixIsRejected") { check(reject(gate().eval(fix(0, 0), "p1", ageMs: 90_000)) == "stale", "") }
test("olderTimestampIsRejected") {
  let g = gate()
  _ = g.eval(fix(0, 10), "p1")
  check(reject(g.eval(fix(50, 5), "p2")) == "out-of-order", "")
}

// --- normal motion ---------------------------------------------------------------

test("secondMovingFixReleasesTheFirst") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  let r = released(g.eval(fix(100, 10), "p2"))
  check(r?.payload == "p1", "\(String(describing: r))")
  check(abs((r?.d ?? 0) - 100) < 1, "displacement \(String(describing: r?.d))")
}
test("flushReleasesTheHeldFix") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  check(g.flush()?.1 == "p1", "")
  check(g.flush() == nil, "second flush")
}

// --- stationary jitter + parked points -------------------------------------------

test("smallMoveShortlyAfterIsJitter") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  check(reject(g.eval(fix(3, 10), "p2")) == "jitter", "")
}
test("jitterRadiusGrowsWithAccuracy") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  check(reject(g.eval(fix(25, 10, acc: 30), "p2")) == "jitter", "")
}
test("smallMoveAfterKeepaliveReleasesHeldFix") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  check(released(g.eval(fix(3, 120), "p2"))?.payload == "p1", "")
}
test("parkedPointIsWrittenTenMinutesAfterTheStop") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  _ = g.eval(fix(3, 120), "p2")
  for i in 2...4 { check(reject(g.eval(fix(4, 120 * Double(i)), "q\(i)")) == "jitter", "q\(i)") }
  let s = snap(g.eval(fix(4, 600), "p3"))
  check(s?.t == t0 + 600_000, "\(String(describing: s))")
}
test("parkedPointIsTheCurrentFixWhenItStaysClose") {
  let g = gate()
  _ = g.eval(fix(0, 0, speed: 0), "p1")
  _ = g.eval(fix(0.3, 120, speed: 0), "p2")
  _ = g.eval(fix(-1.5, 240, speed: 0), "p3")
  let s = snap(g.eval(fix(2, 600, acc: 12, speed: 0, dLngM: 1), "now"))
  let now = fix(2, 600, dLngM: 1)
  check(s?.lat == now.lat && s?.lng == now.lng, "\(String(describing: s))")
  check(s?.acc == 12, "acc \(String(describing: s?.acc))")
}
test("parkedPointFallsBackToTheNearestFixWhenTheCurrentOneStrays") {
  let g = gate()
  _ = g.eval(fix(0, 0, speed: 0), "p1")
  _ = g.eval(fix(12, 120, acc: 20, speed: 0), "p2")
  _ = g.eval(fix(-2, 240, acc: 25, speed: 0, dLngM: 1), "near")
  _ = g.eval(fix(15, 360, acc: 20, speed: 0, dLngM: -8), "p4")
  let s = snap(g.eval(fix(18, 600, acc: 20, speed: 0), "p5"))
  let near = fix(-2, 240, dLngM: 1)
  check(s?.lat == near.lat && s?.lng == near.lng, "\(String(describing: s))")
  check(s?.acc == 25, "acc \(String(describing: s?.acc))")
}
test("blurryIndoorStayStillGetsAPointEveryTenMinutes") {
  let g = gate()
  _ = g.eval(fix(0, 0, acc: 25, speed: 0), "p1")
  let snaps = (1...30).compactMap { i in snap(g.eval(fix(Double(i % 5) - 2, 120 * Double(i), acc: 25, speed: 0), "j\(i)")) }
  check(snaps.map { $0.t } == (1...6).map { t0 + 600_000 * Int64($0) }, "\(snaps.map { $0.t - t0 })")
  check(snaps.allSatisfy { $0.acc == 25 }, "acc")
  for k in 1..<snaps.count { check(snaps[k].lat != snaps[k - 1].lat || snaps[k].lng != snaps[k - 1].lng, "repeat at \(k)") }
}
test("parkedPointsNeverRepeatTheLastCoordinatesExactly") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  for i in 1...5 { _ = g.eval(fix(0, 120 * Double(i)), "same\(i)") }
  check(reject(g.eval(fix(0, 720), "same6")) == "jitter", "")
  let s = snap(g.eval(fix(5, 840), "p2"))
  check(s != nil && abs(s!.lat - (lat0 + 5 / 111_000.0)) < 1e-9, "\(String(describing: s))")
}
test("parkedPointsAreAtLeastTenMinutesApart") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  _ = g.eval(fix(3, 120), "p2")
  let ts = (2...15).compactMap { i in snap(g.eval(fix(1 + Double(i % 3), 120 * Double(i)), "q\(i)"))?.t }
  check(ts == [t0 + 600_000, t0 + 1_200_000, t0 + 1_800_000], "\(ts.map { $0 - t0 })")
}
test("parkedPhoneKeepsTheSpeedCheckStrict") {
  let g = gate()
  _ = g.eval(fix(0, 0, acc: 25, speed: 0), "p1")
  for i in 1...30 { _ = g.eval(fix(2, 120 * Double(i), acc: 25, speed: 0), "j\(i)") }
  check(reject(g.eval(fix(3_000, 3_630, speed: 5), "far")) == "teleport", "")
}
test("keepaliveIsRateLimited") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  _ = g.eval(fix(3, 120), "p2")
  check(reject(g.eval(fix(2, 180), "p3")) == "jitter", "")
}

// --- stationary drift ----------------------------------------------------------------

test("zeroSpeedFixWithinTheCombinedAccuracyIsDrift") {
  let g = gate()
  _ = g.eval(fix(0, 0, acc: 20, speed: 0), "p1")
  check(reject(g.eval(fix(36, 60, acc: 20, speed: 0), "p2")) == "jitter", "")
}
test("walkingFixBeyondTheJitterRadiusIsStillAMove") {
  let g = gate()
  _ = g.eval(fix(0, 0, acc: 20, speed: 0), "p1")
  check(released(g.eval(fix(36, 60, acc: 20, speed: 1.2), "p2")) != nil, "")
}

// --- teleport / re-anchor ----------------------------------------------------------

test("fiveKmInTenSecondsIsATeleport") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  check(reject(g.eval(fix(5_000, 10), "p2")) == "teleport", "")
}
test("threeAgreeingFixesReanchor") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  check(reject(g.eval(fix(5_000, 10), "p2")) == "teleport", "p2")
  check(reject(g.eval(fix(5_030, 20), "p3")) == "teleport", "p3")
  check(released(g.eval(fix(5_060, 30), "p4"))?.payload == "p1", "p4")
}
test("disagreeingTeleportsDoNotReanchor") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  _ = g.eval(fix(5_000, 10), "p2")
  _ = g.eval(fix(9_000, 20), "p3")
  check(reject(g.eval(fix(9_020, 30), "p4")) == "teleport", "")
}
test("bigMoveAfterLongGapIsAccepted") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  check(released(g.eval(fix(20_000, 1_800), "p2")) != nil, "")
}
test("wrongAnchorReleasesOnItsOwnWithTime") {
  let g = gate()
  _ = g.eval(fix(0, 0), "p1")
  check(released(g.eval(fix(5_000, 150), "p2")) != nil, "")
}

// --- zig-zag ---------------------------------------------------------------------------

test("spurThatReturnsToStartIsDropped") {
  let g = gate()
  _ = g.eval(fix(0, 0), "a")
  _ = g.eval(fix(200, 10), "b0")
  check(released(g.eval(fix(500, 20), "spur")) != nil, "spur held, b0 released")
  check(reject(g.eval(fix(210, 30), "back")) == "zigzag", "back")
  let next = holdD(g.eval(fix(300, 40), "c"))
  check(next != nil && abs(next! - 100) < 2, "\(String(describing: next))")
}
test("realOutAndBackBeyondReturnRadiusIsKept") {
  let g = gate()
  _ = g.eval(fix(0, 0), "a")
  _ = g.eval(fix(200, 10), "b")
  _ = g.eval(fix(500, 20), "c")
  check(released(g.eval(fix(260, 30), "d"))?.payload == "c", "")
}

// --- unconfirmed jumps (stationary scatter) ------------------------------------------

test("zeroSpeedScatterAroundAParkedPhoneIsNeverAccepted") {
  let g = gate()
  _ = g.eval(fix(0, 0, speed: 0), "home")
  let scatter: [(Double, Double, Double)] = [
    (400, 0, 30), (-150, 600, 75), (-700, -200, 110), (300, -800, 150),
    (850, 300, 210), (-400, -650, 260), (100, 950, 330), (-900, 250, 380),
  ]
  for (i, s) in scatter.enumerated() {
    let speed: Double? = i % 2 == 0 ? 0 : nil
    let out = g.eval(fix(s.0, s.2, acc: 7 + Double(i), speed: speed, dLngM: s.1), "s\(i)")
    check(reject(out) != nil, "scatter fix \(i) must be rejected, was \(out)")
  }
  let back = g.eval(fix(5, 400, speed: 0), "back")
  check(released(back) != nil || snap(back) != nil, "\(back)")
}
test("relocationWithoutSpeedIsAcceptedOnTheThirdAgreeingFix") {
  let g = gate()
  _ = g.eval(fix(0, 0, speed: 0), "p1")
  check(reject(g.eval(fix(600, 60, speed: nil), "p2")) == "unconfirmed", "p2")
  check(reject(g.eval(fix(620, 120, speed: nil), "p3")) == "unconfirmed", "p3")
  check(released(g.eval(fix(610, 180, speed: nil), "p4"))?.payload == "p1", "p4")
}
test("zeroSpeedFixRightAfterMotionIsAccepted") {
  let g = gate()
  _ = g.eval(fix(0, 0, speed: 8), "p1")
  check(released(g.eval(fix(150, 15, speed: nil), "p2")) != nil, "")
}
test("zeroSpeedJumpLongAfterMotionNeedsConfirmation") {
  let g = gate()
  _ = g.eval(fix(0, 0, speed: 8), "p1")
  check(reject(g.eval(fix(400, 120, speed: 0), "p2")) == "unconfirmed", "")
}
test("walkingSpeedIsNotTreatedAsStationary") {
  let g = gate()
  _ = g.eval(fix(0, 0, speed: 0), "p1")
  check(released(g.eval(fix(100, 80, speed: 1.2), "p2")) != nil, "")
}
test("shortZeroSpeedMoveWithinAccuracyBandIsNotUnconfirmed") {
  let g = gate()
  _ = g.eval(fix(0, 0, speed: 0), "p1")
  check(released(g.eval(fix(60, 30, acc: 25, speed: 0), "p2")) != nil, "")
}
test("holdReportsDisplacementFromAnchor") {
  let g = gate()
  _ = g.eval(fix(0, 0), "a")
  _ = g.flush()
  let d = holdD(g.eval(fix(150, 120), "b"))
  check(d != nil && abs(d! - 150) < 2, "\(String(describing: d))")
}

// --- replayed still nights vs the backend's spoofing check --------------------------
// Same recorded nights as the Android ParkedReplayTest (offsets only, no position).

struct Written { let lat: Double; let lng: Double; let t: Int64 }

/// The backend's rule (noJitterTrap): 20 consecutive points whose distances from their
/// centroid have a standard deviation under 0.5 m, all within 2 m of it, spanning > 10 min.
func backendFlags(_ w: ArraySlice<Written>) -> Bool {
  let n = Double(w.count)
  let cLat = w.reduce(0) { $0 + $1.lat } / n
  let cLng = w.reduce(0) { $0 + $1.lng } / n
  let d = w.map { LocationGateLimits.haversineM(cLat, cLng, $0.lat, $0.lng) }
  let mean = d.reduce(0, +) / n
  let sd = (d.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / n).squareRoot()
  return sd < 0.5 && (d.max() ?? 0) < 2 && w.last!.t - w.first!.t > 600_000
}

func replay(_ name: String) -> [Written] {
  let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    .appendingPathComponent("../android/src/test/resources/parked/\(name)")
  guard let text = try? String(contentsOf: url, encoding: .utf8) else {
    check(false, "cannot read \(url.path)")
    return []
  }
  let g = LocationGate<Int>(movingDistanceM: 20, maxAccuracyM: 35, keepaliveMs: 120_000)
  var out: [Written] = []
  let rows = text.split(separator: "\n").filter { !$0.hasPrefix("#") && !$0.isEmpty }
  for (i, line) in rows.enumerated() {
    let v = line.split(separator: ",").map { Double($0)! }
    let t = t0 + Int64(v[0] * 1000)
    let f = LocationGate<Int>.Fix(
      lat: lat0 + v[1] / 111_000.0, lng: lng0 + v[2] / (111_000.0 * cos(lat0 * .pi / 180)),
      acc: v[3], t: t, speedMps: 0)
    switch g.evaluate(fix: f, ageMs: 0, payload: i) {
    case .release(let fx, _, _): out.append(Written(lat: fx.lat, lng: fx.lng, t: fx.t))
    case .snap(let la, let ln, let ts, _): out.append(Written(lat: la, lng: ln, t: ts))
    default: break
    }
  }
  return out
}

test("theBackendRuleHereFlagsExactRepeats") {
  let repeats = (0..<20).map { Written(lat: 9.98, lng: 76.38, t: Int64($0) * 150_000) }
  check(backendFlags(repeats[...]), "exact repeats not flagged")
}
test("stillNightsNeverTripTheBackendSpoofingCheck") {
  for night in ["still-night-1.csv", "still-night-2.csv"] {
    let w = replay(night)
    check(w.count >= 45, "\(night) wrote \(w.count) points")
    let flagged = w.count >= 20 ? (0...(w.count - 20)).filter { backendFlags(w[$0..<($0 + 20)]) }.count : 0
    check(flagged == 0, "\(night): \(flagged) windows the backend would flag")
  }
}

print("swift gate spec: \(passed) passed, \(failures) failed")
exit(failures == 0 ? 0 : 1)
