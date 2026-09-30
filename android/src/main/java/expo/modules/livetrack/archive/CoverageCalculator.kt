package expo.modules.livetrack.archive

import expo.modules.livetrack.capture.LocationGate

/**
 * Pure per-window summary of captured points: how many, first/last time, and
 * every silence longer than [DEFAULT_GAP_MS] with the straight-line distance
 * the device covered meanwhile. Mirrored by `ios/CoverageCalculator.swift`.
 *
 * A gap that spans several kilometres is the dashboard's "straight line across
 * town": nothing was captured, so nothing can be replayed. A gap with ~0 m is a
 * stationary period where the keepalive did not fire.
 */
object CoverageCalculator {
  data class Pt(val t: Long, val lat: Double, val lng: Double)
  data class Gap(val fromT: Long, val toT: Long, val durationMs: Long, val distanceM: Double)
  data class Result(val count: Int, val firstT: Long?, val lastT: Long?, val gaps: List<Gap>)

  const val DEFAULT_GAP_MS = 600_000L

  fun compute(points: List<Pt>, gapMs: Long = DEFAULT_GAP_MS): Result {
    val sorted = points.sortedBy { it.t }.distinctBy { it.t }
    if (sorted.isEmpty()) return Result(0, null, null, emptyList())
    val gaps = ArrayList<Gap>()
    for (i in 1 until sorted.size) {
      val a = sorted[i - 1]
      val b = sorted[i]
      val dt = b.t - a.t
      if (dt > gapMs) {
        gaps += Gap(a.t, b.t, dt, LocationGate.haversineM(a.lat, a.lng, b.lat, b.lng))
      }
    }
    return Result(sorted.size, sorted.first().t, sorted.last().t, gaps)
  }
}
