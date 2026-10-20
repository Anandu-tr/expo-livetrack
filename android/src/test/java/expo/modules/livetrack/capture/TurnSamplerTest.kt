package expo.modules.livetrack.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixes arrive every ~3 s while moving; a point is written every ~10 s as
 * before, plus at turns, so corners stop being drawn as diagonals.
 * 0.00001 deg ~ 1.11 m.
 */
class TurnSamplerTest {

  private val lat0 = 9.98
  private val lng0 = 76.28
  private val m = 1.0 / 111_000.0

  private fun s() = TurnSampler(intervalMs = 10_000L)

  /** Offer a fix and mark it written when chosen, like the service does. */
  private fun TurnSampler.offer(northM: Double, eastM: Double, tS: Double): Boolean {
    val lat = lat0 + northM * m
    val lng = lng0 + eastM * m / Math.cos(Math.toRadians(lat0))
    val t = (tS * 1000).toLong()
    val write = shouldWrite(lat, lng, t)
    if (write) written(lat, lng, t)
    return write
  }

  @Test fun drivingStraightWritesAboutEveryTenSeconds() {
    val sampler = s()
    // 10 m/s north, a fix every 3 s for a minute.
    val written = (0..20).count { i -> sampler.offer(northM = 30.0 * i, eastM = 0.0, tS = 3.0 * i) }
    assertEquals(7, written) // t = 0, 9, 18, 27, 36, 45, 54 s
  }

  @Test fun aRightAngleTurnIsWrittenAtTheCorner() {
    val sampler = s()
    assertTrue(sampler.offer(0.0, 0.0, 0.0))
    assertTrue(sampler.offer(90.0, 0.0, 9.0)) // interval point, heading north
    assertFalse(sampler.offer(120.0, 0.0, 12.0)) // still north, too soon
    // Turned east: written immediately rather than at t = 18 s.
    assertTrue(sampler.offer(125.0, 28.0, 15.0))
  }

  @Test fun gentleCurvesWaitForTheInterval() {
    val sampler = s()
    sampler.offer(0.0, 0.0, 0.0)
    sampler.offer(90.0, 0.0, 9.0)
    // ~15 degrees off the previous heading.
    assertFalse(sampler.offer(119.0, 8.0, 12.0))
  }

  @Test fun shortLegsDoNotCountAsTurns() {
    val sampler = s()
    sampler.offer(0.0, 0.0, 0.0)
    sampler.offer(90.0, 0.0, 9.0)
    // 90 degrees, but only 8 m from the last point: GNSS wobble, not a turn.
    assertFalse(sampler.offer(90.0, 8.0, 12.0))
  }

  @Test fun theFirstFixIsAlwaysWritten() {
    assertTrue(s().offer(0.0, 0.0, 0.0))
  }
}
