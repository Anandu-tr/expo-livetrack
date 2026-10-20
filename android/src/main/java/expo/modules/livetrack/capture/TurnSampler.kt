package expo.modules.livetrack.capture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Chooses which accepted moving fixes become points. While moving, fixes are
 * requested every few seconds; a point is still written about every
 * [intervalMs] (as before) and additionally where the heading turns by
 * [turnDeg] or more over a leg of at least [minTurnLegM], so the map's straight
 * lines follow corners instead of cutting them.
 *
 * Production (Oct 2026): at one point per 10 s, and 20 s for a quarter of legs
 * (the provider skipped slots), city drives had points ~84 m apart and turns
 * were drawn as diagonals.
 *
 * Pure; single-threaded by contract (the service's main looper).
 */
class TurnSampler(
  private val intervalMs: Long,
  private val turnDeg: Double = TURN_DEG,
  private val minTurnLegM: Double = MIN_TURN_LEG_M,
) {

  private data class P(val lat: Double, val lng: Double, val t: Long)

  /** The last two points actually written; their direction is the reference heading. */
  private var prev: P? = null
  private var last: P? = null

  fun shouldWrite(lat: Double, lng: Double, t: Long): Boolean {
    val l = last ?: return true
    if (t - l.t >= intervalMs - SLACK_MS) return true
    val p = prev ?: return false
    // Both legs must be long enough for a heading to mean anything (GNSS wobble
    // and parked keepalives make short legs point anywhere).
    if (LocationGate.haversineM(p.lat, p.lng, l.lat, l.lng) < minTurnLegM) return false
    if (LocationGate.haversineM(l.lat, l.lng, lat, lng) < minTurnLegM) return false
    return angleBetween(bearing(p.lat, p.lng, l.lat, l.lng), bearing(l.lat, l.lng, lat, lng)) >= turnDeg
  }

  /** Record a point that was written (moving, stop or keepalive alike). */
  fun written(lat: Double, lng: Double, t: Long) {
    prev = last
    last = P(lat, lng, t)
  }

  companion object {
    const val TURN_DEG = 30.0
    const val MIN_TURN_LEG_M = 15.0

    /** Fix-to-fix jitter: write the fix nearest the interval, not the one after it. */
    const val SLACK_MS = 1_500L

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
      val p1 = Math.toRadians(lat1)
      val p2 = Math.toRadians(lat2)
      val dl = Math.toRadians(lng2 - lng1)
      val y = sin(dl) * cos(p2)
      val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
      return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleBetween(a: Double, b: Double): Double {
      val d = abs(a - b) % 360.0
      return if (d > 180.0) 360.0 - d else d
    }
  }
}
