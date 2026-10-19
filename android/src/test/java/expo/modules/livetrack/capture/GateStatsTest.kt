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
    assertEquals(
      "STATS|m=S|ar=12|d=3|a=1|sn=1|acc=1|st=0|oo=0|tp=0|ji=2|zz=0|un=1|pr=1",
      s.format(still = true, arSilentMin = 12),
    )
  }

  @Test fun unknownArAgeAndMovingModeAreExplicit() {
    val out = GateStats().format(still = false, arSilentMin = null)
    assertTrue(out, out.startsWith("STATS|m=M|ar=-1|"))
  }

  @Test fun resetZeroesEverything() {
    val s = GateStats()
    s.delivered()
    s.reject("teleport")
    s.reset()
    assertEquals(
      "STATS|m=M|ar=0|d=0|a=0|sn=0|acc=0|st=0|oo=0|tp=0|ji=0|zz=0|un=0|pr=0",
      s.format(still = false, arSilentMin = 0),
    )
  }

  @Test fun unrecognisedRejectReasonIsCountedSomewhereVisible() {
    val s = GateStats()
    s.reject("something-new")
    assertTrue(s.format(still = false, arSilentMin = 0).endsWith("|pr=0|x=1"))
  }
}
