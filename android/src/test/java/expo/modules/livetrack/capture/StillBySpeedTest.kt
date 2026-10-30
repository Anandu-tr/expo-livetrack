package expo.modules.livetrack.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillBySpeedTest {

  private val t0 = 1_000_000L
  private val min = 60_000L

  @Test fun threeMinutesOfSlowFixesMeanStill() {
    val s = StillBySpeed()
    s.onSpeed(0.2, t0)
    s.onSpeed(0.1, t0 + min)
    assertFalse(s.due(t0 + 3 * min - 1))
    assertTrue(s.due(t0 + 3 * min))
  }

  @Test fun aFixReportingRealSpeedEndsTheRun() {
    val s = StillBySpeed()
    s.onSpeed(0.2, t0)
    s.onSpeed(1.0, t0 + min)
    assertFalse(s.due(t0 + 5 * min))
  }

  @Test fun fixesWithoutSpeedNeitherStartNorEndARun() {
    val s = StillBySpeed()
    s.onSpeed(null, t0)
    assertFalse(s.due(t0 + 5 * min))
    s.onSpeed(0.0, t0 + 5 * min)
    s.onSpeed(null, t0 + 6 * min)
    assertTrue(s.due(t0 + 8 * min))
  }

  // Oct 30, 12:13 (c21a1207): stopped, then off in a vehicle whose network
  // fixes report no speed; accepted moves of 626 m and 982 m went unnoticed and
  // the phone went still mid-ride.
  @Test fun aFarAcceptedMoveEndsTheRun() {
    val s = StillBySpeed()
    s.onSpeed(0.0, t0)
    s.onMove(982.0, 92_000L, t0 + 92_000L)
    assertFalse(s.due(t0 + 3 * min))
  }

  // Oct 29-30 (c21a1207): mid-ride, the only fixes were network fixes far from
  // the last position, rejected by the gate and reporting 0. Counted as slow,
  // three minutes of them put the phone in still mode while it drove on
  // (09:48:40, 10:33:40, 10:37:40, 10:42:40, ...).
  @Test fun farRejectedFixesReportingNoSpeedDoNotStartARun() {
    val s = StillBySpeed()
    s.onSpeed(0.0, t0, far = true)
    s.onSpeed(0.0, t0 + min, far = true)
    assertFalse(s.due(t0 + 5 * min))
  }

  @Test fun farRejectedFixesLeaveARunTheyDidNotStartAlone() {
    // A stop with fixes near the phone, then far scatter: still a stop.
    val s = StillBySpeed()
    s.onSpeed(0.0, t0)
    s.onSpeed(0.0, t0 + min, far = true)
    assertTrue(s.due(t0 + 3 * min))
  }

  @Test fun aFarFixReportingRealSpeedStillEndsTheRun() {
    val s = StillBySpeed()
    s.onSpeed(0.0, t0)
    s.onSpeed(4.0, t0 + min, far = true)
    assertFalse(s.due(t0 + 5 * min))
  }

  @Test fun smallMovesLeaveTheRunAlone() {
    // Indoor drift accepted as small moves is not a ride.
    val s = StillBySpeed()
    s.onSpeed(0.0, t0)
    s.onMove(30.0, 20_000L, t0 + min)
    assertTrue(s.due(t0 + 3 * min))
  }

  @Test fun slowMovesLeaveTheRunAlone() {
    val s = StillBySpeed()
    s.onSpeed(0.0, t0)
    s.onMove(120.0, 120_000L, t0 + 2 * min) // 1 m/s
    assertTrue(s.due(t0 + 3 * min))
  }

  @Test fun resetEndsTheRun() {
    val s = StillBySpeed()
    s.onSpeed(0.0, t0)
    s.reset()
    assertFalse(s.due(t0 + 5 * min))
  }
}
