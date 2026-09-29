package expo.modules.livetrack.capture

import expo.modules.livetrack.capture.LocationGate.Fix
import expo.modules.livetrack.capture.LocationGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable spec for [LocationGate]. Plain JUnit — no Android on the classpath.
 *
 * Distances are built from latitude offsets: 0.00001° ≈ 1.11 m, so
 * `lat + 0.0009` ≈ 100 m and `lat + 0.045` ≈ 5 km.
 */
class LocationGateTest {

  private val lat0 = 9.9800
  private val lng0 = 76.3800
  private val t0 = 1_788_000_000_000L

  private fun gate() = LocationGate<String>(movingDistanceM = 20.0, maxAccuracyM = 35.0, keepaliveMs = 120_000L)

  private fun fix(dLatM: Double, dtS: Double, acc: Double = 10.0) =
    Fix(lat0 + dLatM / 111_000.0, lng0, acc, t0 + (dtS * 1000).toLong())

  private fun <T> LocationGate<T>.eval(f: Fix, payload: T, ageMs: Long = 0L) = evaluate(f, ageMs, payload)

  // --- accuracy / staleness ------------------------------------------------

  @Test fun firstFixIsHeldNotWritten() {
    val g = gate()
    val out = g.eval(fix(0.0, 0.0), "p1")
    assertTrue(out is Outcome.Hold)
  }

  @Test fun noAccuracyIsRejected() {
    val g = gate()
    assertEquals(Outcome.Reject("accuracy"), g.eval(fix(0.0, 0.0, acc = -1.0), "p1"))
  }

  @Test fun poorAccuracyIsRejected() {
    val g = gate()
    assertEquals(Outcome.Reject("accuracy"), g.eval(fix(0.0, 0.0, acc = 36.0), "p1"))
  }

  @Test fun staleFixIsRejected() {
    val g = gate()
    assertEquals(Outcome.Reject("stale"), g.eval(fix(0.0, 0.0), "p1", ageMs = 90_000L))
  }

  @Test fun olderTimestampIsRejected() {
    val g = gate()
    g.eval(fix(0.0, 10.0), "p1")
    assertEquals(Outcome.Reject("out-of-order"), g.eval(fix(50.0, 5.0), "p2"))
  }

  // --- normal motion -------------------------------------------------------

  @Test fun secondMovingFixReleasesTheFirst() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    val out = g.eval(fix(100.0, 10.0), "p2")
    assertTrue(out is Outcome.Release)
    assertEquals("p1", (out as Outcome.Release).payload)
    assertEquals(100.0, out.displacementM, 1.0)
  }

  @Test fun flushReleasesTheHeldFix() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    val flushed = g.flush()
    assertNotNull(flushed)
    assertEquals("p1", flushed!!.second)
    assertNull(g.flush())
  }

  // --- stationary jitter + keepalive --------------------------------------

  @Test fun smallMoveShortlyAfterIsJitter() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    assertEquals(Outcome.Reject("jitter"), g.eval(fix(3.0, 10.0), "p2"))
  }

  @Test fun jitterRadiusGrowsWithAccuracy() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    // 25 m move with 30 m accuracy: inside max(movingDistanceM, acc).
    assertEquals(Outcome.Reject("jitter"), g.eval(fix(25.0, 10.0, acc = 30.0), "p2"))
  }

  @Test fun smallMoveAfterKeepaliveReleasesHeldFix() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    val out = g.eval(fix(3.0, 120.0), "p2")
    assertTrue(out is Outcome.Release)
    assertEquals("p1", (out as Outcome.Release).payload)
  }

  @Test fun subsequentKeepaliveIsASnapAtTheAnchor() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    g.eval(fix(3.0, 120.0), "p2") // releases p1, nothing held now
    val out = g.eval(fix(4.0, 240.0), "p3")
    assertTrue(out is Outcome.Snap)
    val snap = out as Outcome.Snap
    assertEquals(lat0, snap.lat, 1e-9)
    assertEquals(t0 + 240_000L, snap.t)
  }

  @Test fun keepaliveIsRateLimited() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    g.eval(fix(3.0, 120.0), "p2")
    assertEquals(Outcome.Reject("jitter"), g.eval(fix(2.0, 180.0), "p3"))
  }

  // --- teleport / re-anchor -------------------------------------------------

  @Test fun fiveKmInTenSecondsIsATeleport() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    assertEquals(Outcome.Reject("teleport"), g.eval(fix(5_000.0, 10.0), "p2"))
  }

  @Test fun threeAgreeingFixesReanchor() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    assertEquals(Outcome.Reject("teleport"), g.eval(fix(5_000.0, 10.0), "p2"))
    assertEquals(Outcome.Reject("teleport"), g.eval(fix(5_030.0, 20.0), "p3"))
    val out = g.eval(fix(5_060.0, 30.0), "p4")
    assertTrue(out is Outcome.Release)
    assertEquals("p1", (out as Outcome.Release).payload)
  }

  @Test fun disagreeingTeleportsDoNotReanchor() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    g.eval(fix(5_000.0, 10.0), "p2")
    g.eval(fix(9_000.0, 20.0), "p3") // > 150 m from the candidate: restarts the count
    assertEquals(Outcome.Reject("teleport"), g.eval(fix(9_020.0, 30.0), "p4"))
  }

  @Test fun bigMoveAfterLongGapIsAccepted() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    val out = g.eval(fix(20_000.0, 1_800.0), "p2") // 20 km in 30 min = 11 m/s
    assertTrue(out is Outcome.Release)
  }

  @Test fun wrongAnchorReleasesOnItsOwnWithTime() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    // 5 km needs > 143 s at 35 m/s; a fix 150 s later is accepted without the counter.
    val out = g.eval(fix(5_000.0, 150.0), "p2")
    assertTrue(out is Outcome.Release)
  }

  // --- zig-zag --------------------------------------------------------------

  @Test fun spurThatReturnsToStartIsDropped() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "a")
    g.eval(fix(200.0, 10.0), "b0") // releases a, holds b0 (real movement)
    val spur = g.eval(fix(500.0, 20.0), "spur") // 300 m away, plausible speed: held
    assertTrue(spur is Outcome.Release) // b0 released
    // Back to within 10 m of b0: the spur is dropped, this fix is jitter vs b0.
    assertEquals(Outcome.Reject("zigzag"), g.eval(fix(210.0, 30.0), "back"))
    // Nothing held now; the next real move is held again with b0 (200 m) as anchor.
    val next = g.eval(fix(300.0, 40.0), "c")
    assertTrue(next is Outcome.Hold)
    assertEquals(100.0, (next as Outcome.Hold).displacementM, 2.0)
  }

  @Test fun realOutAndBackBeyondReturnRadiusIsKept() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "a")
    g.eval(fix(200.0, 10.0), "b")
    g.eval(fix(500.0, 20.0), "c")
    // Comes back but stops 60 m short of b: not a zig-zag, c is released.
    val out = g.eval(fix(260.0, 30.0), "d")
    assertTrue(out is Outcome.Release)
    assertEquals("c", (out as Outcome.Release).payload)
  }

  // --- displacement reporting for still-mode exit --------------------------

  @Test fun holdReportsDisplacementFromAnchor() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "a")
    g.flush()
    val out = g.eval(fix(150.0, 120.0), "b")
    assertTrue(out is Outcome.Hold)
    assertEquals(150.0, (out as Outcome.Hold).displacementM, 2.0)
  }
}
