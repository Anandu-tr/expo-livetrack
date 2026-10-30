package expo.modules.livetrack.capture

import expo.modules.livetrack.capture.LocationGate.Fix
import expo.modules.livetrack.capture.LocationGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  // --- parked points: one per 10 min, the real fix nearest the last one ---------
  // Production (Oct 2026): a keepalive every 2 min at each fix's own position
  // drew a 50-line scribble over a two-hour office stay at high zoom; keeping
  // only sharp fixes then left an indoor stay without a point for 71 min.

  @Test fun parkedPointIsWrittenTenMinutesAfterTheStop() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    g.eval(fix(3.0, 120.0), "p2") // releases p1 (the stop), nothing held now
    for (i in 2..4) assertEquals(Outcome.Reject("jitter"), g.eval(fix(4.0, 120.0 * i), "q$i"))
    val out = g.eval(fix(4.0, 600.0), "p3")
    assertTrue(out is Outcome.Snap)
    // Stamped now: the newest point says the phone is still here.
    assertEquals(t0 + 600_000L, (out as Outcome.Snap).t)
  }

  @Test fun parkedPointIsTheCurrentFixWhenItStaysClose() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "p1")
    g.eval(fix(0.3, 120.0, speed = 0.0), "p2") // releases p1 at lat0; the closest fix of the window
    g.eval(fix(-1.5, 240.0, speed = 0.0), "p3")
    val out = g.eval(fix(2.0, 600.0, acc = 12.0, speed = 0.0, dLngM = 1.0), "now") // 2.2 m from p1
    assertTrue(out is Outcome.Snap)
    val snap = out as Outcome.Snap
    // Written as it is: always picking the closest fix strips the natural jitter
    // the backend's spoofing check looks for (it flags 20 pings within 0.5 m).
    val now = fix(2.0, 600.0, dLngM = 1.0)
    assertEquals(now.lat, snap.lat, 1e-12)
    assertEquals(now.lng, snap.lng, 1e-12)
    assertEquals(12.0, snap.acc, 1e-9)
  }

  @Test fun parkedPointFallsBackToTheNearestFixWhenTheCurrentOneStrays() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "p1")
    g.eval(fix(12.0, 120.0, acc = 20.0, speed = 0.0), "p2") // releases p1 at lat0
    g.eval(fix(-2.0, 240.0, acc = 25.0, speed = 0.0, dLngM = 1.0), "near")
    g.eval(fix(15.0, 360.0, acc = 20.0, speed = 0.0, dLngM = -8.0), "p4")
    val out = g.eval(fix(18.0, 600.0, acc = 20.0, speed = 0.0), "p5") // 18 m from p1
    assertTrue(out is Outcome.Snap)
    val snap = out as Outcome.Snap
    // Indoors the current fix can be 20-30 m off: the real fix that stayed
    // nearest p1 is written instead (with its own accuracy), so the line barely moves.
    val near = fix(-2.0, 240.0, dLngM = 1.0)
    assertEquals(near.lat, snap.lat, 1e-12)
    assertEquals(near.lng, snap.lng, 1e-12)
    assertEquals(25.0, snap.acc, 1e-9)
  }

  @Test fun blurryIndoorStayStillGetsAPointEveryTenMinutes() {
    val g = gate()
    g.eval(fix(0.0, 0.0, acc = 25.0, speed = 0.0), "p1")
    val outs = (1..30).map { i -> g.eval(fix((i % 5) - 2.0, 120.0 * i, acc = 25.0, speed = 0.0), "j$i") }
    val snaps = outs.filterIsInstance<Outcome.Snap>()
    // An hour at 25 m accuracy: a point every 10 min after the stop's own row.
    assertEquals((1..6).map { t0 + 600_000L * it }, snaps.map { it.t })
    assertTrue(snaps.all { it.acc == 25.0 })
    snaps.zipWithNext().forEach { (a, b) -> assertTrue(a.lat != b.lat || a.lng != b.lng) }
  }

  @Test fun parkedPointsNeverRepeatTheLastCoordinatesExactly() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    // A provider replaying p1's exact position adds nothing, and exact repeats
    // are what the backend reads as a spoofed location.
    for (i in 1..5) g.eval(fix(0.0, 120.0 * i), "same$i") // the first releases p1
    assertEquals(Outcome.Reject("jitter"), g.eval(fix(0.0, 720.0), "same6"))
    // The next genuinely new fix keeps it alive.
    val out = g.eval(fix(5.0, 840.0), "p2")
    assertTrue(out is Outcome.Snap)
    assertEquals(lat0 + 5.0 / 111_000.0, (out as Outcome.Snap).lat, 1e-9)
  }

  @Test fun parkedPointsAreAtLeastTenMinutesApart() {
    val g = gate()
    g.eval(fix(0.0, 0.0), "p1")
    g.eval(fix(3.0, 120.0), "p2") // releases p1
    val outs = (2..15).map { i -> g.eval(fix(1.0 + (i % 3), 120.0 * i), "q$i") }
    val snapTimes = outs.filterIsInstance<Outcome.Snap>().map { it.t }
    assertEquals(listOf(t0 + 600_000L, t0 + 1_200_000L, t0 + 1_800_000L), snapTimes)
  }

  @Test fun parkedPhoneKeepsTheSpeedCheckStrict() {
    val g = gate()
    g.eval(fix(0.0, 0.0, acc = 25.0, speed = 0.0), "p1")
    // An hour parked: only a point per 10 min is written...
    for (i in 1..30) g.eval(fix(2.0, 120.0 * i, acc = 25.0, speed = 0.0), "j$i")
    // ...but the time base kept up, so 3 km in 30 s is still a teleport.
    assertEquals(Outcome.Reject("teleport"), g.eval(fix(3_000.0, 3_630.0, speed = 5.0), "far"))
  }

  // --- stationary drift --------------------------------------------------------
  // Production (Oct 2026): parked indoors at 20-34 m accuracy, fixes 20-37 m away
  // reporting 0 km/h were written as moves and drawn as lines across the road.

  @Test fun zeroSpeedFixWithinTheCombinedAccuracyIsDrift() {
    val g = gate()
    g.eval(fix(0.0, 0.0, acc = 20.0, speed = 0.0), "p1")
    assertEquals(Outcome.Reject("jitter"), g.eval(fix(36.0, 60.0, acc = 20.0, speed = 0.0), "p2"))
  }

  @Test fun walkingFixBeyondTheJitterRadiusIsStillAMove() {
    val g = gate()
    g.eval(fix(0.0, 0.0, acc = 20.0, speed = 0.0), "p1")
    val out = g.eval(fix(36.0, 60.0, acc = 20.0, speed = 1.2), "p2")
    assertTrue(out is Outcome.Release)
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

  // Production, Oct 26 (c21a1207), as offsets: a fix reporting about 1 km/h
  // landed 486 m to the side of a slow drive and the next point came back 50 m
  // from where it left. A long spur that reports no motion of its own may come
  // back that far and still be a spur.
  @Test fun noSpeedSpurComingBackWithinAShareOfItsLengthIsDropped() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 4.4), "a0")
    g.eval(fix(100.0, 10.0, speed = 4.4), "a")
    val spur = g.eval(fix(100.0, 28.0, speed = 0.3, dLngM = 486.0), "spur")
    assertTrue(spur is Outcome.Release) // a released, the spur held
    val back = g.eval(fix(150.0, 58.0, speed = 0.0), "back") // 50 m from a
    assertTrue("the spur must not be written, was $back", back is Outcome.Hold)
    assertEquals(50.0, (back as Outcome.Hold).displacementM, 2.0)
    assertEquals("back", g.flush()!!.second)
  }

  @Test fun spurReportingMotionKeepsTheFortyMetreReturnRule() {
    // Same shape, but the detour carries GNSS speed: a real out-and-back.
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 4.4), "a0")
    g.eval(fix(100.0, 10.0, speed = 4.4), "a")
    g.eval(fix(100.0, 28.0, speed = 5.0, dLngM = 486.0), "detour")
    val out = g.eval(fix(150.0, 58.0, speed = 5.0), "back")
    assertTrue(out is Outcome.Release)
    assertEquals("detour", (out as Outcome.Release).payload)
  }

  // --- still transition ------------------------------------------------------
  // Production, Oct 26 (c21a1207): both spurs that reached the map were the held
  // fix, written by the flush when the phone went still, which skipped the vote.

  @Test fun stillTransitionHoldsAFarNoSpeedFixUntilTheNextFixesVote() {
    // 13:19, as offsets: a fix at speed 0 landed 1,388 m away just before the
    // phone went still; the next fixes came back 42-44 m from the start. Here the
    // fixes before it report vehicle speed, so the pace check leaves it to the
    // vote (with the walking pace it really had, see the next test).
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 4.0), "a0")
    g.eval(fix(64.0, 28.0, speed = 4.0), "a")
    g.eval(fix(64.0, 78.0, speed = 0.0, dLngM = 1_388.0), "spike")
    assertNull("the spike must wait for the next fixes", g.flushAtStill())
    // Still mode: a fix every two minutes, back near the start, no speed.
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(108.0, 270.0, speed = 0.0), "c1"))
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(104.0, 390.0, speed = 0.0), "c2"))
    val out = g.eval(fix(106.0, 510.0, speed = 0.0), "c3")
    assertTrue("the spike must be dropped, was $out", out is Outcome.Hold)
    assertEquals("c3", g.flush()!!.second)
  }

  @Test fun theOct26SpikeAtItsRealPaceIsStoppedAtTheGate() {
    // 13:17:49 the phone reported 2 km/h; 109 s later a fix at speed 0 landed
    // 1,388 m away. Something vouched for it as motion (a fix with speed in the
    // minute before, modelled here), yet no pace explains it, so it never
    // reaches the hold.
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.55), "a0")
    g.eval(fix(64.0, 29.0, acc = 17.5, speed = 0.55), "a")
    g.eval(fix(69.0, 100.0, speed = 2.2), "vouch") // jitter, but it proves motion
    assertEquals(
      Outcome.Reject("inconsistent"),
      g.eval(fix(64.0, 138.0, acc = 12.4, speed = 0.0, dLngM = 1_388.0), "spike"),
    )
  }

  @Test fun stillTransitionThenReturnDropsADriveSpur() {
    // 13:59, as offsets: the 486 m spur was held when the phone went still and
    // the first fix after it came back 50 m from the start.
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 4.4), "a0")
    g.eval(fix(100.0, 10.0, speed = 4.4), "a")
    g.eval(fix(100.0, 28.0, speed = 0.3, dLngM = 486.0), "spike")
    assertNull("the spike must wait for the next fix", g.flushAtStill())
    val out = g.eval(fix(150.0, 58.0, speed = 0.0), "back")
    assertTrue("the spike must be dropped, was $out", out is Outcome.Hold)
    assertEquals("back", g.flush()!!.second)
  }

  @Test fun heldFarJumpIsWrittenOnceTheNextFixesStayWithIt() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 4.0), "a0")
    g.eval(fix(64.0, 28.0, speed = 4.0), "a")
    g.eval(fix(64.0, 78.0, speed = 0.0, dLngM = 1_388.0), "new place")
    assertNull(g.flushAtStill())
    // Over two minutes later the phone is still there: the held fix was real.
    val out = g.eval(fix(68.0, 210.0, speed = 0.0, dLngM = 1_388.0), "there")
    assertTrue("was $out", out is Outcome.Release)
    assertEquals("new place", (out as Outcome.Release).payload)
  }

  @Test fun stillTransitionWritesTheLastPositionOfARealStop() {
    // Far but still moving when held: a real position.
    val moving = gate()
    moving.eval(fix(0.0, 0.0, speed = 8.0), "a0")
    moving.eval(fix(150.0, 15.0, speed = 8.0), "a")
    moving.eval(fix(300.0, 30.0, speed = 3.0), "stop")
    assertEquals("stop", moving.flushAtStill()?.second)
    // Stopped already, but close to the last written point.
    val stopped = gate()
    stopped.eval(fix(0.0, 0.0, speed = 8.0), "a0")
    stopped.eval(fix(150.0, 15.0, speed = 8.0), "a")
    stopped.eval(fix(210.0, 30.0, speed = 0.0), "stop")
    assertEquals("stop", stopped.flushAtStill()?.second)
  }

  @Test fun stillTransitionWritesTheFirstHeldFixOfARun() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "first")
    assertEquals("first", g.flushAtStill()?.second)
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

  // --- walking pace (Oct 29, c21a1207) -------------------------------------
  // Fixes reporting walking speed landed 77-600 m away from a real walk; the
  // 126 km/h ceiling let them through and the map drew a starburst.

  /** A walk: one fix every 16 s, 20 m apart, each reporting 1.2 m/s. */
  private fun LocationGate<String>.walk(steps: Int): List<Outcome<String>> =
    (0 until steps).map { i -> eval(fix(20.0 * i, 16.0 * i, speed = 1.2), "w$i") }

  @Test fun walkingFixThatOutrunsItsOwnSpeedIsRejected() {
    val g = gate()
    g.walk(3) // w2 held at 40 m
    // 07:38 shape: 245 m on in 49 s while reporting 4 km/h.
    assertEquals(Outcome.Reject("inconsistent"), g.eval(fix(285.0, 81.0, acc = 6.5, speed = 1.1), "scatter"))
    val next = g.eval(fix(60.0, 97.0, speed = 1.2), "w3") // the walk goes on
    assertTrue(next is Outcome.Release)
    assertEquals("w2", (next as Outcome.Release).payload)
  }

  @Test fun walkingAtItsReportedPaceIsKept() {
    assertTrue(gate().walk(12).drop(1).all { it is Outcome.Release })
  }

  @Test fun aFixReportingVehicleSpeedIsLeftToTheSpeedCeiling() {
    val g = gate()
    g.walk(3)
    // Boarded a bus: 300 m on in 30 s, reporting 10 m/s.
    assertTrue(g.eval(fix(340.0, 62.0, speed = 10.0), "bus") is Outcome.Release)
  }

  @Test fun walkingPaceFixesAgreeingOnANewPlaceAreAcceptedOnTheThird() {
    val g = gate()
    g.walk(3)
    assertEquals(Outcome.Reject("inconsistent"), g.eval(fix(285.0, 81.0, speed = 1.1), "x1"))
    assertEquals(Outcome.Reject("inconsistent"), g.eval(fix(290.0, 85.0, speed = 1.1), "x2"))
    assertTrue(g.eval(fix(288.0, 89.0, speed = 1.1), "x3") !is Outcome.Reject)
  }

  // --- travel without GPS (Oct 30, c21a1207) --------------------------------
  // Parked, then a 7.5 km ride with network fixes only (no speed): each landed
  // farther along the road and each was rejected as an unconfirmed jump, for
  // 30 minutes.

  @Test fun farFixesMovingSteadilyAwayAreAcceptedAsTravel() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "parked")
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(230.0, 120.0, acc = 15.0, speed = null, dLngM = 240.0), "r1"))
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(470.0, 240.0, acc = 15.0, speed = null, dLngM = 450.0), "r2"))
    // 980 m out: indoor scatter reaches that far, so not yet.
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(700.0, 360.0, acc = 15.0, speed = null, dLngM = 690.0), "r3"))
    val out = g.eval(fix(930.0, 480.0, acc = 15.0, speed = null, dLngM = 920.0), "r4") // 1.3 km
    assertTrue("travel must be accepted, was $out", out !is Outcome.Reject)
  }

  @Test fun scatterDriftingOutwardInOneDirectionIsNotTravel() {
    // c21a1207's home, night of Oct 19-20 (old build, polled every 15 s), as
    // offsets from home: the scatter drifted away along one bearing for several
    // fixes before snapping back, which a trail rule without a distance floor
    // took for a ride.
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "home")
    // Fixes too far apart for the three-agree rule, so only a trail rule could take them.
    val runs = listOf(
      listOf(Triple(110.0, 220.0, 120.0), Triple(114.0, 270.0, 136.0), Triple(156.0, 407.0, 151.0)), // 247 -> 292 -> 436 m
      listOf(Triple(-480.0, 129.0, 600.0), Triple(-588.0, 487.0, 628.0), Triple(-698.0, 386.0, 648.0), Triple(-799.0, 133.0, 666.0), Triple(-886.0, 145.0, 685.0)), // 497 -> 898 m
    )
    for (run in runs) {
      for ((n, e, t) in run) {
        val out = g.eval(fix(n, t, speed = 0.0, dLngM = e), "s")
        assertTrue("scatter at $n,$e must be rejected, was $out", out is Outcome.Reject)
      }
      g.eval(fix(3.0, run.last().third + 20.0, speed = 0.0), "home again") // snaps back
    }
  }

  @Test fun aFixBackNearThePhoneBreaksTheTravelTrail() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "parked")
    g.eval(fix(230.0, 120.0, acc = 15.0, speed = null, dLngM = 240.0), "r1")
    g.eval(fix(470.0, 240.0, acc = 15.0, speed = null, dLngM = 450.0), "r2")
    g.eval(fix(3.0, 300.0, speed = 0.0), "home") // indoor scatter, not a ride
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(700.0, 360.0, acc = 15.0, speed = null, dLngM = 690.0), "r3"))
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(930.0, 480.0, acc = 15.0, speed = null, dLngM = 920.0), "r4"))
  }

  @Test fun travelIsSuspectedOnceTwoFarFixesHeadTheSameWay() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "parked")
    g.eval(fix(230.0, 120.0, acc = 15.0, speed = null, dLngM = 240.0), "r1")
    assertFalse(g.travelSuspected())
    g.eval(fix(470.0, 240.0, acc = 15.0, speed = null, dLngM = 450.0), "r2")
    assertTrue(g.travelSuspected())
  }

  @Test fun scatterInDifferentDirectionsIsNotSuspectedTravel() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 0.0), "home")
    g.eval(fix(400.0, 30.0, speed = 0.0), "s0")
    g.eval(fix(-150.0, 75.0, speed = null, dLngM = 600.0), "s1")
    assertFalse(g.travelSuspected())
  }

  // --- a vehicle report from Activity Recognition ---------------------------
  // Oct 27-28 (c21a1207): driving on network fixes only, 14-28 fixes per
  // 10 min rejected as unconfirmed jumps while AR reported a vehicle.

  @Test fun aVehicleReportVouchesForFixesWithoutSpeed() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 8.0), "p1")
    g.noteMotion(t0 + 100_000L)
    val out = g.eval(fix(400.0, 120.0, speed = null), "p2")
    assertTrue("was $out", out is Outcome.Release)
  }

  @Test fun aVehicleReportVouchesForAMinuteOnly() {
    val g = gate()
    g.eval(fix(0.0, 0.0, speed = 8.0), "p1")
    g.noteMotion(t0 + 10_000L)
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(400.0, 120.0, speed = null), "p2"))
  }

  // c21a1207's network fixes report a speed of exactly 0 rather than none
  // (Oct 30, 10:51-10:52, in a vehicle). Vouched for, that 0 is no pace at all;
  // the walking-pace check rejected them as if the phone had crawled.
  @Test fun aVouchedFixReportingZeroSpeedIsNotHeldToThatPace() {
    val g = gate()
    g.eval(fix(0.0, 0.0, acc = 12.0, speed = 0.0), "n1")
    g.noteMotion(t0 + 25_000L)
    val out = g.eval(fix(300.0, 30.0, acc = 14.0, speed = 0.0), "n2")
    assertTrue("was $out", out is Outcome.Release)
  }

  @Test fun anUnvouchedFixReportingZeroSpeedStillNeedsConfirmation() {
    val g = gate()
    g.eval(fix(0.0, 0.0, acc = 12.0, speed = 0.0), "n1")
    assertEquals(Outcome.Reject("unconfirmed"), g.eval(fix(300.0, 30.0, acc = 14.0, speed = 0.0), "n2"))
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
