import Foundation

/// Pure per-window summary of captured points. Mirrors
/// `android/.../archive/CoverageCalculator.kt` — keep in sync.
enum CoverageCalculator {
  struct Pt {
    let t: Int64
    let lat: Double
    let lng: Double
  }

  struct Gap {
    let fromT: Int64
    let toT: Int64
    let durationMs: Int64
    let distanceM: Double
  }

  struct Result {
    let count: Int
    let firstT: Int64?
    let lastT: Int64?
    let gaps: [Gap]
  }

  static let defaultGapMs: Int64 = 600_000

  static func compute(_ points: [Pt], gapMs: Int64 = defaultGapMs) -> Result {
    var seen = Set<Int64>()
    let sorted = points.sorted { $0.t < $1.t }.filter { seen.insert($0.t).inserted }
    guard let first = sorted.first, let last = sorted.last else {
      return Result(count: 0, firstT: nil, lastT: nil, gaps: [])
    }
    var gaps: [Gap] = []
    if sorted.count > 1 {
      for i in 1..<sorted.count {
        let a = sorted[i - 1]
        let b = sorted[i]
        let dt = b.t - a.t
        if dt > gapMs {
          gaps.append(Gap(
            fromT: a.t, toT: b.t, durationMs: dt,
            distanceM: LocationGateLimits.haversineM(a.lat, a.lng, b.lat, b.lng)
          ))
        }
      }
    }
    return Result(count: sorted.count, firstT: first.t, lastT: last.t, gaps: gaps)
  }
}
