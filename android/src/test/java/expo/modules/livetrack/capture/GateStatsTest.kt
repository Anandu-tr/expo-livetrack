package expo.modules.livetrack.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateStatsTest {

  @Test fun formatsEveryCounterInAFixedOrder() {
    val s = GateStats()
    repeat(3) { s.delivered() }
    s.accepted()
    s.snap()
    s.probe()
    s.reject("accuracy")
    s.reject("jitter")
    s.reject("jitter")
    s.reject("unconfirmed")
    s.reject("inconsistent")
    s.sigMotion()
    s.motion(std = 0.12, moving = false)
    s.motion(std = 0.834, moving = true)
    assertEquals(
      "STATS|m=S|ar=12|d=3|a=1|sn=1|acc=1|st=0|oo=0|tp=0|ji=2|zz=0|un=1|ic=1|pr=1|fc=0|sm=1|mc=2|mv=1|ms=83",
      s.format(still = true, arSilentMin = 12, sigMotion = true),
    )
  }

  @Test fun unknownArAgeAndMovingModeAreExplicit() {
    val out = GateStats().format(still = false, arSilentMin = null)
    assertTrue(out, out.startsWith("STATS|m=M|ar=-1|"))
  }

  @Test fun aPhoneWithoutTheSignificantMotionSensorSaysSo() {
    val s = GateStats()
    s.sigMotion() // cannot happen without the sensor; the field still says "absent"
    assertTrue(s.format(still = true, arSilentMin = 0, sigMotion = false).contains("|sm=-1|"))
  }

  @Test fun resetZeroesEverything() {
    val s = GateStats()
    s.delivered()
    s.reject("teleport")
    s.fast(90_000L)
    s.sigMotion()
    s.motion(std = 1.5, moving = true)
    s.reset()
    assertEquals(
      "STATS|m=M|ar=0|d=0|a=0|sn=0|acc=0|st=0|oo=0|tp=0|ji=0|zz=0|un=0|ic=0|pr=0|fc=0|sm=0|mc=0|mv=0|ms=0",
      s.format(still = false, arSilentMin = 0, sigMotion = true),
    )
  }

  @Test fun unrecognisedRejectReasonIsCountedSomewhereVisible() {
    val s = GateStats()
    s.reject("something-new")
    assertTrue(s.format(still = false, arSilentMin = 0).endsWith("|ms=0|x=1"))
  }

  @Test fun fastCadenceTimeIsReportedInWholeSeconds() {
    val s = GateStats()
    s.fast(90_400L)
    s.fast(29_700L)
    s.fast(-5_000L) // a clock oddity never subtracts
    assertTrue(s.format(still = false, arSilentMin = 0).contains("|pr=0|fc=120|"))
  }

  @Test fun theLargestMotionReadingIsKept() {
    // The field data that tunes MotionCheck.MOVING_STD: how hard parked and riding phones shake.
    val s = GateStats()
    s.motion(std = 0.31, moving = false)
    s.motion(std = 0.05, moving = false)
    assertTrue(s.format(still = true, arSilentMin = 0).contains("|mc=2|mv=0|ms=31"))
  }
}
