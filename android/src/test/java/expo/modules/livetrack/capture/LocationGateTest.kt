package expo.modules.livetrack.capture

import expo.modules.livetrack.capture.LocationGate.Fix
import expo.modules.livetrack.capture.LocationGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

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

  /** A real GNSS fix while moving reports a speed; the pre-existing cases model that. */
  private val movingSpeed = 5.0

  private fun fix(
    dLatM: Double,
    dtS: Double,
    acc: Double = 10.0,
    monotonicDtS: Double = dtS,
    speed: Double? = movingSpeed,
    dLngM: Double = 0.0,
  ) =
    Fix(
      lat0 + dLatM / 111_000.0,
      lng0 + dLngM / (111_000.0 * cos(Math.toRadians(lat0))),
      acc,
      t0 + (dtS * 1000).toLong(),
      t0 + (monotonicDtS * 1000).toLong(),
      speed,
    )

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

  @Test fun backwardWallClockJumpDoesNotWedgeTheGate() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    // Wall clock jumps back an hour (NTP correction / cold boot before time
    // sync) while monotonic time advances normally by 10s: a genuinely new,
    // in-order fix must still be accepted, not rejected as "out-of-order"
    // forever (the wedge bug: only acceptance advances the anchor).
    val out = g.eval(fix(100.0, dtS = -3600.0, monotonicDtS = 10.0), "p2")
    assertTrue(out is Outcome.Release)
    assertEquals("p1", (out as Outcome.Release).payload)
  }

  @Test fun outOfOrderIsJudgedByMonotonicTimeNotWallClock() {
    val g = gate()
    g.eval(fix(0.0, 10.0), "p1")
    // Wall-clock t looks later than the anchor's, but monotonic time is
    // earlier — a real out-of-order delivery must still be rejected.
    assertEquals(
      Outcome.Reject("out-of-order"),
      g.eval(fix(50.0, dtS = 20.0, monotonicDtS = 5.0), "p2"),
    )
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

  @Test fun subsequentKeepaliveIsWrittenAtTheFixesOwnPosition() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    g.eval(fix(3.0, 120.0), "p2") // releases p1, nothing held now
    val out = g.eval(fix(4.0, 240.0), "p3")
    assertTrue(out is Outcome.Snap)
    val snap = out as Outcome.Snap
    // Not the anchor's position: repeating its exact coordinates every keepalive
    // was flagged as a fake location by the backend's fraud check.
    assertEquals(lat0 + 4.0 / 111_000.0, snap.lat, 1e-9)
    assertEquals(t0 + 240_000L, snap.t)
  }

  @Test fun keepaliveRepeatingTheLastWrittenCoordinatesExactlyIsSkipped() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    g.eval(fix(3.0, 120.0), "p2") // releases p1 at lat0
    // A provider replay of p1's exact position adds nothing, and exact repeats
    // are what the backend reads as a spoofed location.
    assertEquals(Outcome.Reject("jitter"), g.eval(fix(0.0, 240.0), "p3"))
    // The next genuinely new fix still keeps the phone alive on the map.
    assertTrue(g.eval(fix(5.0, 250.0), "p4") is Outcome.Snap)
  }

  @Test fun consecutiveKeepalivesNeverRepeatExactCoordinates() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    g.eval(fix(3.0, 120.0), "p2")
    assertTrue(g.eval(fix(4.0, 240.0), "p3") is Outcome.Snap)
    assertEquals(Outcome.Reject("jitter"), g.eval(fix(4.0, 360.0), "p4"))
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

  // --- unconfirmed jumps (stationary scatter) -------------------------------
  // Production, Oct 2026: with Activity Recognition silent the service polled
  // every 10 s indoors and got fixes reporting speed 0 / no speed and 7-17 m
  // accuracy that were really 300-1000 m off. Each passed the speed check
  // because the allowance grows with time since the last accepted fix.

  @Test fun zeroSpeedScatterAroundAParkedPhoneIsNeverAccepted() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "home")
    // Pairwise > 150 m apart, so the re-anchor counter never gets 3 agreeing.
    val scatter = listOf(
      Triple(400.0, 0.0, 30.0), Triple(-150.0, 600.0, 75.0), Triple(-700.0, -200.0, 110.0),
      Triple(300.0, -800.0, 150.0), Triple(850.0, 300.0, 210.0), Triple(-400.0, -650.0, 260.0),
      Triple(100.0, 950.0, 330.0), Triple(-900.0, 250.0, 380.0),
    )
    scatter.forEachIndexed { i, (dLat, dLng, dt) ->
      val speed = if (i % 2 == 0) 0.0 else null
      val out = g.eval(fix(dLat, dt, acc = 7.0 + i, speed = speed, dLngM = dLng), "s$i")
      assertTrue("scatter fix $i must be rejected, was $out", out is Outcome.Reject)
    }
    // The real position is still the anchor: a fix back home is jitter / keepalive.
    val back = g.eval(fix(5.0, 400.0, speed = 0.0), "back")
    assertTrue(back is Outcome.Release || back is Outcome.Snap)
  }

  @Test fun relocationWithoutSpeedIsAcceptedOnTheThirdAgreeingFix() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "p1")
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(600.0, 60.0, speed = null), "p2"))
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(620.0, 120.0, speed = null), "p3"))
    val out = g.eval(fix(610.0, 180.0, speed = null), "p4")
    assertTrue(out is Outcome.Release)
    assertEquals("p1", (out as Outcome.Release).payload)
  }

  @Test fun zeroSpeedFixRightAfterMotionIsAccepted() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 8.0), "p1")
    // A network fix mid-drive: no speed of its own, but the phone was just moving.
    val out = g.eval(fix(150.0, 15.0, speed = null), "p2")
    assertTrue(out is Outcome.Release)
  }

  @Test fun zeroSpeedJumpLongAfterMotionNeedsConfirmation() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 8.0), "p1")
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(400.0, 120.0, speed = 0.0), "p2"))
  }

  @Test fun walkingSpeedIsNotTreatedAsStationary() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "p1")
    val out = g.eval(fix(100.0, 80.0, speed = 1.2), "p2")
    assertTrue(out is Outcome.Release)
  }

  @Test fun shortZeroSpeedMoveWithinAccuracyBandIsNotUnconfirmed() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "p1")
    // 60 m with 25 m accuracy: below max(75 m, 3 x acc) — an ordinary small move.
    val out = g.eval(fix(60.0, 30.0, acc = 25.0, speed = 0.0), "p2")
    assertTrue(out is Outcome.Release)
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
