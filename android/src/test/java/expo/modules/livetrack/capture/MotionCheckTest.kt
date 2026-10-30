package expo.modules.livetrack.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionCheckTest {

  private val t0 = 1_000_000L
  private val g = 9.81f

  /** [n] readings whose magnitude swings by [swing] m/s² around gravity, in any orientation. */
  private fun MotionCheck.feed(n: Int, swing: Float) {
    repeat(n) { i ->
      val m = g + if (i % 2 == 0) swing else -swing
      // Same magnitude split over two axes: the check must not care how the phone lies.
      if (i % 3 == 0) sample(0f, 0f, m) else sample(m * 0.6f, 0f, m * 0.8f)
    }
  }

  @Test fun aPhoneLyingStillIsNotMoving() {
    val c = MotionCheck()
    c.start(t0)
    c.feed(150, 0.02f) // sensor noise
    val v = c.finish(t0 + 3_000L)
    assertNotNull(v)
    assertFalse(v!!.moving)
  }

  @Test fun vibrationOfARideIsMoving() {
    val c = MotionCheck()
    c.start(t0)
    c.feed(150, 0.8f)
    val v = c.finish(t0 + 3_000L)
    assertTrue(v!!.moving)
    assertEquals(0.8, v.std, 0.01)
  }

  @Test fun tooFewReadingsGiveNoVerdict() {
    // A throttled sensor says nothing either way.
    val c = MotionCheck()
    c.start(t0)
    c.feed(5, 2.0f)
    assertNull(c.finish(t0 + 3_000L))
    assertFalse(c.movingRecently(t0 + 3_000L))
  }

  @Test fun readingsOutsideACheckAreIgnored() {
    val c = MotionCheck()
    c.feed(150, 2.0f)
    c.start(t0)
    c.feed(150, 0.02f)
    assertFalse(c.finish(t0 + 3_000L)!!.moving)
  }

  @Test fun checksAreSpacedOut() {
    val c = MotionCheck()
    assertTrue(c.due(t0))
    c.start(t0)
    c.finish(t0 + 3_000L)
    assertFalse(c.due(t0 + MotionCheck.GAP_MS - 1))
    assertTrue(c.due(t0 + MotionCheck.GAP_MS))
  }

  @Test fun noSecondCheckWhileOneRuns() {
    val c = MotionCheck()
    c.start(t0)
    assertFalse(c.due(t0 + 10 * MotionCheck.GAP_MS))
    c.finish(t0 + 3_000L)
    assertTrue(c.due(t0 + 10 * MotionCheck.GAP_MS))
  }

  @Test fun aMovingVerdictCountsForAWhileOnly() {
    val c = MotionCheck()
    c.start(t0)
    c.feed(150, 0.8f)
    c.finish(t0 + 3_000L)
    assertTrue(c.movingRecently(t0 + 3_000L + MotionCheck.TRUST_MS))
    assertFalse(c.movingRecently(t0 + 3_000L + MotionCheck.TRUST_MS + 1))
  }

  @Test fun aStillVerdictReplacesAMovingOne() {
    val c = MotionCheck()
    c.start(t0)
    c.feed(150, 0.8f)
    c.finish(t0 + 3_000L)
    c.start(t0 + MotionCheck.GAP_MS)
    c.feed(150, 0.02f)
    c.finish(t0 + MotionCheck.GAP_MS + 3_000L)
    assertFalse(c.movingRecently(t0 + MotionCheck.GAP_MS + 3_000L))
  }
}
