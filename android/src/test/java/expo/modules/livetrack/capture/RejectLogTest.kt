package expo.modules.livetrack.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class RejectLogTest {

  private fun RejectLog.far(reason: String = "unconfirmed") =
    take(reason, still = true, lat = 9.9765412, lng = 76.2876549, acc = 23.6, speedMps = 0.0)

  @Test fun aFarRejectBecomesACompactRow() {
    assertEquals("REJ|un|l=9.97654|g=76.28765|a=24|s=0.0", RejectLog().far())
  }

  @Test fun everyFarReasonIsKept() {
    val log = RejectLog()
    assertEquals("REJ|tp|", log.far("teleport")!!.take(7))
    assertEquals("REJ|ic|", log.far("inconsistent")!!.take(7))
  }

  @Test fun noSpeedIsADash() {
    val r = RejectLog().take("teleport", still = false, lat = 10.0, lng = 76.0, acc = 8.0, speedMps = null)
    assertEquals("REJ|tp|l=10.00000|g=76.00000|a=8|s=-", r)
  }

  @Test fun nearbyRejectsAreNotLogged() {
    val log = RejectLog()
    for (reason in listOf("jitter", "zigzag", "stale", "out-of-order")) assertNull(reason, log.far(reason))
  }

  @Test fun blurryFixesAreLoggedOnlyWhileMoving() {
    val log = RejectLog()
    assertNull(log.take("accuracy", still = true, lat = 10.0, lng = 76.0, acc = 60.0, speedMps = null))
    assertEquals(
      "REJ|acc|l=10.00000|g=76.00000|a=60|s=-",
      log.take("accuracy", still = false, lat = 10.0, lng = 76.0, acc = 60.0, speedMps = null),
    )
    // Too blurry to say anything about a route.
    assertNull(log.take("accuracy", still = false, lat = 10.0, lng = 76.0, acc = 150.0, speedMps = null))
  }

  @Test fun rowsAreCappedPerWindowUntilReset() {
    val log = RejectLog()
    repeat(RejectLog.FAR_PER_WINDOW) { log.far() }
    assertNull(log.far())
    repeat(RejectLog.BLURRY_PER_WINDOW) { log.take("accuracy", still = false, lat = 1.0, lng = 1.0, acc = 50.0, speedMps = null) }
    assertNull(log.take("accuracy", still = false, lat = 1.0, lng = 1.0, acc = 50.0, speedMps = null))
    log.reset()
    assertEquals("REJ|un|", log.far()!!.take(7))
  }

  @Test fun theRowIgnoresTheDeviceLocale() {
    // A comma-decimal locale must not turn the coordinates into "9,97654".
    val saved = Locale.getDefault()
    try {
      Locale.setDefault(Locale.GERMANY)
      assertEquals("REJ|un|l=9.97654|g=76.28765|a=24|s=0.0", RejectLog().far())
    } finally {
      Locale.setDefault(saved)
    }
  }
}
