package expo.modules.livetrack.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastCadenceTest {

  @Test fun vehicleSpeedTurnsItOn() {
    val f = FastCadence()
    assertTrue(f.onSpeed(8.0, now = 1_000L, still = false))
    assertTrue(f.on)
  }

  @Test fun walkingPaceNeverDoes() {
    val f = FastCadence()
    for ((i, v) in listOf(1.4, 2.0, 2.9, 0.0).withIndex()) f.onSpeed(v, now = i * 10_000L, still = false)
    assertFalse(f.on)
  }

  @Test fun staysOnThroughATrafficLight() {
    val f = FastCadence()
    f.onSpeed(10.0, now = 0L, still = false)
    f.onSpeed(0.0, now = 60_000L, still = false)
    assertFalse(f.update(now = 120_000L, still = false))
    assertTrue(f.on)
    // Two minutes without a fast fix: back to the normal cadence.
    assertTrue(f.update(now = 120_001L, still = false))
    assertFalse(f.on)
  }

  @Test fun stillModeTurnsItOffAndKeepsItOff() {
    val f = FastCadence()
    f.onSpeed(10.0, now = 0L, still = false)
    assertTrue(f.update(now = 1_000L, still = true))
    assertFalse(f.on)
    assertFalse(f.onSpeed(10.0, now = 2_000L, still = true))
    assertFalse(f.on)
  }

  @Test fun leavingStillRightAfterAFastFixGoesStraightToFast() {
    val f = FastCadence()
    // The fast fix that ends still mode arrives while still...
    f.onSpeed(9.0, now = 5_000L, still = true)
    assertFalse(f.on)
    // ...and the exit then picks the fast cadence at once.
    assertTrue(f.update(now = 5_001L, still = false))
    assertTrue(f.on)
  }

  @Test fun reportsTimeSpentFastAndResetsIt() {
    val f = FastCadence()
    f.onSpeed(10.0, now = 0L, still = false)
    f.update(now = 90_000L, still = true)
    assertEquals(90_000L, f.takeSpentMs(now = 100_000L))
    assertEquals(0L, f.takeSpentMs(now = 110_000L))
    // A spell still running is counted up to now, and only once.
    f.onSpeed(10.0, now = 200_000L, still = false)
    assertEquals(60_000L, f.takeSpentMs(now = 260_000L))
    assertEquals(40_000L, f.takeSpentMs(now = 300_000L))
  }
}
