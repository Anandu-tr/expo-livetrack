package expo.modules.livetrack.capture

import expo.modules.livetrack.capture.LocationGate.Fix
import expo.modules.livetrack.capture.LocationGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Replays real still nights through [LocationGate] and runs the backend's
 * spoofing check ("Possible Fake Location", its `noJitterTrap`) on the points
 * that would be written.
 *
 * Production (Oct 2026): a parked-point rule that always wrote the fix nearest
 * the previous point stripped the natural GPS jitter. Replayed, 25 of one
 * night's 32 windows fell inside the backend's limits.
 */
class ParkedReplayTest {

  private class Written(val lat: Double, val lng: Double, val t: Long)

  /**
   * The backend's rule as its code has it: 20 consecutive points whose distances
   * from their centroid have a standard deviation under 0.5 m, all within 2 m of
   * it, spanning more than 10 minutes.
   */
  private fun flagged(w: List<Written>): Boolean {
    val cLat = w.sumOf { it.lat } / w.size
    val cLng = w.sumOf { it.lng } / w.size
    val d = w.map { LocationGate.haversineM(cLat, cLng, it.lat, it.lng) }
    val mean = d.average()
    val sd = sqrt(d.sumOf { (it - mean) * (it - mean) } / d.size)
    return sd < 0.5 && d.max() < 2.0 && w.last().t - w.first().t > 10 * 60_000L
  }

  /** Every alignment: the backend's windows start wherever the day's other rows put them. */
  private fun flaggedWindows(points: List<Written>) = points.windowed(20).count { flagged(it) }

  /** Feeds a recorded night (offsets in metres, see the resource header) through the gate. */
  private fun replay(resource: String): List<Written> {
    val lat0 = 9.98
    val lng0 = 76.38
    val t0 = 1_788_000_000_000L
    val gate = LocationGate<Int>(movingDistanceM = 20.0, maxAccuracyM = 35.0, keepaliveMs = 120_000L)
    val out = mutableListOf<Written>()
    javaClass.getResourceAsStream(resource)!!.bufferedReader().useLines { lines ->
      lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEachIndexed { i, line ->
        val (s, north, east, acc) = line.split(",").map { it.toDouble() }
        val t = t0 + (s * 1000).toLong()
        val fix = Fix(lat0 + north / 111_000.0, lng0 + east / (111_000.0 * cos(Math.toRadians(lat0))), acc, t, t, 0.0)
        when (val o = gate.evaluate(fix, 0L, i)) {
          is Outcome.Release -> out += Written(o.fix.lat, o.fix.lng, o.fix.t)
          is Outcome.Snap -> out += Written(o.lat, o.lng, o.t)
          else -> Unit
        }
      }
    }
    return out
  }

  @Test fun theBackendRuleHereFlagsExactRepeats() {
    // 90eb4447, Oct 18: one position resent every 2-5 min -> "20 pings with only 0.000m GPS jitter".
    val repeats = (0 until 20).map { Written(9.98, 76.38, it * 150_000L) }
    assertTrue(flagged(repeats))
  }

  @Test fun stillNightsNeverTripTheBackendSpoofingCheck() {
    for (night in listOf("/parked/still-night-1.csv", "/parked/still-night-2.csv")) {
      val written = replay(night)
      // About 9 h at one point per 10 min: enough windows for the check to mean something.
      assertTrue("$night wrote ${written.size} points", written.size >= 45)
      assertEquals("$night: windows the backend would flag", 0, flaggedWindows(written))
    }
  }
}
