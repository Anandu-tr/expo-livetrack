package expo.modules.livetrack.capture

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, platform-free noise gate applied to every fix before it is buffered.
 *
 * Mirrored 1:1 by `ios/LocationGate.swift` — keep the two in sync. No Android
 * imports on purpose so it runs under plain JUnit. Single-threaded by contract
 * (the capture callback delivers on one looper).
 *
 * Why it exists (all observed in production data, Sep 2026):
 *  - Fixes with good reported accuracy that are still hundreds of metres off
 *    (single-point outliers, A→B→A bounces in hilly terrain). An accuracy gate
 *    cannot catch these; an implied-speed gate plus a zig-zag lookahead can.
 *  - Stationary jitter: a parked device wanders 10–40 m and paints a blob.
 *  - Cached / out-of-order fixes after a service restart.
 *  - (Oct 2026) A parked phone polled indoors gets fixes claiming speed 0 and
 *    ~10 m accuracy that land 300-1000 m away; every one passed the speed check
 *    and the map drew a starburst. Such jumps now need confirmation.
 *  - (Oct 29) Walking, fixes reporting walking pace landed 77-600 m away: now
 *    held to the distance their own speed explains.
 *  - (Oct 30) A ride on network fixes only (no speed) looked like that scatter
 *    for 30 minutes: far fixes heading steadily away now confirm as travel.
 *
 * Model: every accepted fix is HELD for one cycle so the next fix can vote on
 * whether it was a spur (zig-zag). The previously held fix is RELEASED when the
 * next one is accepted, so writes lag capture by one fix. [flush] releases the
 * held fix on demand (stop); [flushAtStill] does so at the still transition
 * unless the held fix may be a spur.
 *
 * @param T opaque payload the caller wants back when the fix is released
 *          (Android passes the fully-built row so metadata captured at arrival
 *          time — battery, activity, mock — travels with the fix).
 */
class LocationGate<T>(
  private val movingDistanceM: Double,
  private val maxAccuracyM: Double,
  private val keepaliveMs: Long,
  private val snapIntervalMs: Long = SNAP_INTERVAL_MS,
) {

  /**
   * Minimal view of a fix; `acc < 0` means "no accuracy reported".
   *
   * @param t wall-clock fix time (`Location.time`) — carried through to
   *   released/snapped payloads, where a real timestamp is what matters.
   * @param monotonicT monotonic fix time (`elapsedRealtime`-based) — the ONLY
   *   time base used for dt/anchor math below. `t` alone was tried first and
   *   has a wedge bug: a backward wall-clock jump (NTP correction, manual
   *   clock change, cold boot before time sync) makes every later fix look
   *   "out-of-order" forever, since only acceptance advances the anchor and
   *   acceptance is exactly what a negative dt blocks.
   * @param speedMps the fix's own reported speed, or null when it reports none
   *   (network-derived fixes usually don't). See [UNCONFIRMED_MIN_JUMP_M].
   */
  data class Fix(
    val lat: Double,
    val lng: Double,
    val acc: Double,
    val t: Long,
    val monotonicT: Long,
    val speedMps: Double? = null,
  )

  sealed class Outcome<out T> {
    /** Dropped. `reason` is one of accuracy|stale|out-of-order|teleport|jitter|unconfirmed|inconsistent|zigzag. */
    data class Reject(val reason: String) : Outcome<Nothing>()

    /** Accepted and held for the zig-zag lookahead; nothing to write yet. */
    data class Hold(val displacementM: Double) : Outcome<Nothing>()

    /**
     * The previously held fix is released for writing; the new fix is now held.
     * [atStop]: released because the device stayed put for a keepalive interval,
     * i.e. it is the last real position before a stop and should always be drawn.
     */
    data class Release<T>(
      val fix: Fix,
      val payload: T,
      val displacementM: Double,
      val atStop: Boolean = false,
    ) : Outcome<T>()

    /**
     * Parked point: the caller writes a row at [lat]/[lng] with accuracy [acc],
     * stamped with the current fix's time [t]. The position is the current fix,
     * or, when that strays more than [PARKED_CLOSE_M] from the last written row,
     * the fix seen since then that stayed nearest it; never that row's exact
     * coordinates. Due [snapIntervalMs] after the last written row.
     */
    data class Snap(val lat: Double, val lng: Double, val t: Long, val acc: Double) : Outcome<Nothing>()
  }

  private class Held<T>(val fix: Fix, val payload: T)

  /** Last fix released for writing. */
  private var emitted: Fix? = null

  /** Accepted fix awaiting the lookahead vote. */
  private var held: Held<T>? = null

  /** Monotonic time base for dt. Advances on accept and on every keepalive interval spent in place. */
  private var anchorMonotonicT: Long = 0L

  // Re-anchor candidate, shared by the teleport and unconfirmed-jump gates: both
  // mean "far from the anchor", and agreeing fixes are the cure for either.
  private var cLat = 0.0
  private var cLng = 0.0
  private var cMonotonicT = 0L
  private var cCount = 0

  /** A far fix on the candidate trail, with its distance and bearing from the anchor. */
  private class TrailFix(val fix: Fix, val fromAnchorM: Double, val bearingDeg: Double)

  /** The same far fixes, kept as a trail: a ride shows as fixes heading away together. */
  private val trail = ArrayList<TrailFix>()

  /** Monotonic time of the last fix whose own speed proved motion; null = none yet. */
  private var lastMotionMonotonicT: Long? = null

  // The last row handed out for writing (release, keepalive or flush).
  private var lastOutLat: Double? = null
  private var lastOutLng: Double? = null

  /** Monotonic time of that row; null = none yet. */
  private var lastOutMonotonicT: Long? = null

  /** Parked: the fix nearest the last written row since it was written, and its distance. */
  private var parkedBest: Fix? = null
  private var parkedBestM = Double.MAX_VALUE

  /** The fix everything is measured against: the held one if any, else the last released. */
  private val anchor: Fix?
    get() = held?.fix ?: emitted

  fun evaluate(fix: Fix, ageMs: Long, payload: T): Outcome<T> {
    if (fix.acc.isNaN() || fix.acc < 0.0 || fix.acc > maxAccuracyM) return Outcome.Reject("accuracy")
    if (ageMs > MAX_FIX_AGE_MS) return Outcome.Reject("stale")
    if ((fix.speedMps ?: 0.0) >= MOTION_SPEED_MPS) lastMotionMonotonicT = fix.monotonicT

    val a = anchor
    if (a == null) {
      held = Held(fix, payload)
      anchorMonotonicT = fix.monotonicT
      return Outcome.Hold(0.0)
    }

    val dt = fix.monotonicT - anchorMonotonicT
    if (dt <= 0L) return Outcome.Reject("out-of-order")

    val d = haversineM(a.lat, a.lng, fix.lat, fix.lng)
    val speed = d / (max(dt, MIN_DT_MS) / 1000.0)

    if (speed > MAX_SPEED_MPS) {
      // Allowed distance grows with dt, so a wrong anchor D metres away releases by
      // itself after D / MAX_SPEED_MPS seconds. The candidate counter releases it
      // sooner when several consecutive fixes agree on the new place.
      return if (confirmCandidate(fix)) accept(fix, payload, d) else Outcome.Reject("teleport")
    }

    if (d < max(movingDistanceM, fix.acc) || isDrift(fix, a, d)) {
      resetCandidate()
      if (dt >= keepaliveMs) {
        // Still here after a keepalive interval: refresh the time base even when
        // nothing is written, so the speed check stays strict for a parked phone.
        anchorMonotonicT = fix.monotonicT
        val h = held
        if (h != null) {
          // The held real fix is the last position before the stop: always drawn.
          held = null
          emitted = h.fix
          val out = release(h, d, atStop = true)
          noteParked(fix)
          return out
        }
      }
      if (held == null) {
        noteParked(fix)
        if (isKeepaliveDue(fix)) return keepalive(fix)
      }
      return Outcome.Reject("jitter")
    }

    if (isUnconfirmedJump(fix, d)) {
      return if (confirmCandidate(fix)) accept(fix, payload, d) else Outcome.Reject("unconfirmed")
    }
    if (outrunsOwnSpeed(fix, a)) {
      return if (confirmCandidate(fix)) accept(fix, payload, d) else Outcome.Reject("inconsistent")
    }
    resetCandidate()

    return accept(fix, payload, d)
  }

  /**
   * A fix reporting walking pace that landed farther away than that pace (both
   * fixes' accuracy included) can explain. Production (Oct 29): fixes reporting
   * 1-4 km/h landed 77-600 m from a real walk, under the 126 km/h ceiling, and
   * the map drew a starburst. A fix the last written point explains is a return
   * from a held spur, which the zig-zag vote judges.
   */
  private fun outrunsOwnSpeed(fix: Fix, anchor: Fix): Boolean {
    if (fix.speedMps == null) return false // no speed: the unconfirmed-jump check's case
    if (paceExplains(fix, anchor, anchorMonotonicT)) return false
    val e = emitted
    return !(held != null && e != null && paceExplains(fix, e, e.monotonicT))
  }

  private fun paceExplains(fix: Fix, ref: Fix, refMonotonicT: Long): Boolean {
    val dtS = (fix.monotonicT - refMonotonicT) / 1000.0
    if (dtS <= 0.0 || dtS > PACE_CHECK_MAX_DT_S) return true // too long ago to say
    val pace = max(fix.speedMps ?: 0.0, ref.speedMps ?: 0.0)
    if (pace >= WALKING_PACE_MAX_MPS) return true // vehicle speeds: the speed ceiling judges
    // Neither reports motion, yet motion is vouched for: no pace to judge by. Some
    // phones' network fixes report 0 rather than no speed (c21a1207, Oct 30, in a
    // vehicle), and were rejected here as if the phone had crawled.
    if (pace < STILL_SPEED_MPS && !claimsStationary(fix)) return true
    val d = haversineM(ref.lat, ref.lng, fix.lat, fix.lng)
    return d <= max(UNCONFIRMED_MIN_JUMP_M, PACE_FACTOR * pace * dtS + fix.acc + ref.acc + PACE_MARGIN_M)
  }

  /**
   * Far fixes rejected since the last one near the anchor seem to be heading
   * away together: a ride rather than scatter. The service leaves still mode.
   */
  fun travelSuspected(): Boolean =
    trail.size >= 2 && trail.last().fromAnchorM >= TRAVEL_SUSPECT_MIN_DISTANCE_M && headsAway(TRAVEL_NOISE_M)

  /**
   * Motion reported from outside the fixes, i.e. Activity Recognition saying
   * IN_VEHICLE or ON_BICYCLE: speed-less fixes count as moving for
   * [MOTION_MEMORY_MS] after it. Production (Oct 27-28): driving on network
   * fixes only, every far one was rejected as unconfirmed while AR reported a
   * vehicle. Walking is not enough; that is where the scatter was.
   */
  fun noteMotion(monotonicT: Long) {
    val last = lastMotionMonotonicT
    if (last == null || monotonicT > last) lastMotionMonotonicT = monotonicT
  }

  /**
   * A big jump from a fix that reports no motion of its own, with no recent fix
   * that did. A parked phone indoors produces exactly this: fixes claiming
   * speed 0 and 10 m accuracy while landing 300-1000 m away. The speed gate
   * can't catch them because its allowance grows with time since the last
   * accepted fix. Genuine moves either carry speed (GNSS) or repeat at the new
   * place, which [confirmCandidate] accepts.
   */
  private fun isUnconfirmedJump(fix: Fix, d: Double): Boolean =
    claimsStationary(fix) && d > max(UNCONFIRMED_MIN_JUMP_M, UNCONFIRMED_ACC_FACTOR * fix.acc)

  /**
   * Stationary drift: a fix claiming no motion that landed within the two fixes'
   * combined accuracy of the anchor. Production (Oct 2026): parked indoors at
   * 20-34 m accuracy, fixes 20-37 m away at 0 km/h were written as moves and
   * drawn as lines across the road.
   */
  private fun isDrift(fix: Fix, anchor: Fix, d: Double): Boolean =
    claimsStationary(fix) && d < fix.acc + anchor.acc

  /** No motion of its own (speed missing or ~0) and no recent fix that proved motion. */
  private fun claimsStationary(fix: Fix): Boolean {
    val recentMotion = lastMotionMonotonicT?.let { fix.monotonicT - it <= MOTION_MEMORY_MS } ?: false
    return reportsNoMotion(fix) && !recentMotion
  }

  private fun reportsNoMotion(fix: Fix): Boolean = fix.speedMps == null || fix.speedMps < STILL_SPEED_MPS

  /**
   * Parked points, one per [snapIntervalMs] so the newest point never says the
   * phone went quiet. Each is the current fix when it stays within
   * [PARKED_CLOSE_M] of the previous point, otherwise the real fix that stayed
   * nearest it, so the line barely moves yet keeps GPS's natural jitter.
   * Production (Oct 2026): one keepalive every keepaliveMs at each fix's own
   * position drew a 50-line scribble over a two-hour office stay; replaying an
   * indoor stay (fixes at 20-34 m) this way moves the line ~2 m per point instead
   * of ~23 m. Never an exact repeat: the backend flags 20 consecutive points as
   * spoofing when their distances from the centroid vary by under 0.5 m.
   */
  private fun noteParked(fix: Fix) {
    val lat = lastOutLat ?: return
    val lng = lastOutLng ?: return
    if (fix.lat == lat && fix.lng == lng) return
    val m = haversineM(lat, lng, fix.lat, fix.lng)
    if (m < parkedBestM) {
      parkedBest = fix
      parkedBestM = m
    }
  }

  private fun isKeepaliveDue(fix: Fix): Boolean =
    parkedBest != null && (lastOutMonotonicT?.let { fix.monotonicT - it >= snapIntervalMs } ?: true)

  private fun keepalive(now: Fix): Outcome<T> {
    val b = if (staysClose(now)) now else parkedBest ?: return Outcome.Reject("jitter")
    markOut(b.lat, b.lng, now.monotonicT)
    return Outcome.Snap(b.lat, b.lng, now.t, b.acc)
  }

  /**
   * The current fix is close enough to the last point to be written as it is.
   * Always writing the nearest fix instead stripped the natural jitter: replayed
   * still nights then fell inside the backend's spoofing check (20 points within
   * 0.5 m) in 25 of 32 windows.
   */
  private fun staysClose(fix: Fix): Boolean {
    val lat = lastOutLat ?: return false
    val lng = lastOutLng ?: return false
    if (fix.lat == lat && fix.lng == lng) return false
    return haversineM(lat, lng, fix.lat, fix.lng) <= PARKED_CLOSE_M
  }

  private fun markOut(lat: Double, lng: Double, monotonicT: Long) {
    lastOutLat = lat
    lastOutLng = lng
    lastOutMonotonicT = monotonicT
    parkedBest = null
    parkedBestM = Double.MAX_VALUE
  }

  /**
   * Count [fix] towards the re-anchor candidate; true once [REANCHOR_COUNT]
   * consecutive far fixes agreed within [REANCHOR_RADIUS_M].
   */
  private fun confirmCandidate(fix: Fix): Boolean {
    if (cCount > 0 && fix.monotonicT > cMonotonicT && haversineM(cLat, cLng, fix.lat, fix.lng) <= REANCHOR_RADIUS_M) {
      cCount++
    } else {
      cLat = fix.lat
      cLng = fix.lng
      cCount = 1
    }
    cMonotonicT = fix.monotonicT
    noteTrail(fix)
    if (cCount >= REANCHOR_COUNT || isTravel()) {
      resetCandidate()
      return true
    }
    return false
  }

  private fun resetCandidate() {
    cCount = 0
    trail.clear()
  }

  /**
   * A ride: the trail heads away and is already farther out than indoor scatter
   * reaches. Production (Oct 19-20 nights, c21a1207): scatter drifted outward
   * along one bearing for 3-5 fixes up to ~900 m before snapping back; real
   * rides (Oct 30) were 1.4-4 km out when the trail would have confirmed them.
   */
  private fun isTravel(): Boolean =
    trail.size >= TRAVEL_MIN_FIXES && trail.last().fromAnchorM >= TRAVEL_MIN_DISTANCE_M &&
      headsAway(TRAVEL_MIN_PROGRESS_M)

  private fun noteTrail(fix: Fix) {
    val a = anchor ?: return
    trail += TrailFix(fix, haversineM(a.lat, a.lng, fix.lat, fix.lng), bearingDeg(a.lat, a.lng, fix.lat, fix.lng))
    while (trail.size > TRAVEL_MAX_FIXES || fix.monotonicT - trail.first().fix.monotonicT > TRAVEL_WINDOW_MS) {
      trail.removeAt(0)
    }
  }

  /**
   * The trail moves away from the anchor by at least [minProgressM], every fix
   * on the bearing of the newest one, never falling back by more than GNSS-free
   * noise, each step possible at the speed ceiling. Production (Oct 30): parked,
   * then a 7.5 km ride on network fixes only, each landing farther along the road
   * and each rejected for 30 minutes. Indoor scatter lands in every direction.
   */
  private fun headsAway(minProgressM: Double): Boolean {
    val last = trail.last()
    if (last.fromAnchorM - trail.first().fromAnchorM < minProgressM) return false
    for (i in trail.indices) {
      val p = trail[i]
      if (angleBetweenDeg(p.bearingDeg, last.bearingDeg) > TRAVEL_BEARING_TOLERANCE_DEG) return false
      if (i == 0) continue
      val q = trail[i - 1]
      if (p.fromAnchorM < q.fromAnchorM - TRAVEL_NOISE_M) return false
      val dtS = max((p.fix.monotonicT - q.fix.monotonicT) / 1000.0, 1.0)
      if (haversineM(q.fix.lat, q.fix.lng, p.fix.lat, p.fix.lng) > MAX_SPEED_MPS * dtS + q.fix.acc + p.fix.acc) return false
    }
    return true
  }

  /**
   * Release the held fix, if any, without waiting for a successor. Call on stop
   * and on the still-transition so the last real position is never stranded.
   */
  fun flush(): Pair<Fix, T>? {
    val h = held ?: return null
    held = null
    emitted = h.fix
    markOut(h.fix.lat, h.fix.lng, h.fix.monotonicT)
    return h.fix to h.payload
  }

  /**
   * [flush] for the still transition, where the held fix is normally the last
   * position before the stop. One far from the last written row that reports
   * no motion may be a spur: production (Oct 26) wrote both spurs that reached
   * the map this way, skipping the lookahead vote. It stays held so the next
   * fixes decide: dropped if they come back near the start, written if they
   * stay with it (the keepalive release) or carry on from it.
   */
  fun flushAtStill(): Pair<Fix, T>? {
    val h = held ?: return null
    val prev = emitted
    if (prev != null && reportsNoMotion(h.fix) && haversineM(prev.lat, prev.lng, h.fix.lat, h.fix.lng) > ZIGZAG_LEG_M) {
      return null
    }
    return flush()
  }

  private fun release(h: Held<T>, d: Double, atStop: Boolean = false): Outcome<T> {
    markOut(h.fix.lat, h.fix.lng, h.fix.monotonicT)
    return Outcome.Release(h.fix, h.payload, d, atStop)
  }

  private fun accept(fix: Fix, payload: T, d: Double): Outcome<T> {
    val prev = emitted
    val h = held
    if (h != null && prev != null && isZigZag(prev, h.fix, fix)) {
      // The held fix was a spur: prev → spur → back near prev. Drop the spur and
      // judge the new fix against prev alone. No Snap can have happened while a
      // fix was held, so prev's monotonic time is the right time base to fall
      // back to.
      held = null
      anchorMonotonicT = prev.monotonicT
      val back = haversineM(prev.lat, prev.lng, fix.lat, fix.lng)
      if (back < max(movingDistanceM, fix.acc)) return Outcome.Reject("zigzag")
      held = Held(fix, payload)
      anchorMonotonicT = fix.monotonicT
      return Outcome.Hold(back)
    }

    anchorMonotonicT = fix.monotonicT
    held = Held(fix, payload)
    if (h != null) {
      emitted = h.fix
      return release(h, d)
    }
    return Outcome.Hold(d)
  }

  private fun isZigZag(a: Fix, b: Fix, c: Fix): Boolean {
    val ab = haversineM(a.lat, a.lng, b.lat, b.lng)
    val bc = haversineM(b.lat, b.lng, c.lat, c.lng)
    if (ab <= ZIGZAG_LEG_M || bc <= ZIGZAG_LEG_M) return false
    return haversineM(a.lat, a.lng, c.lat, c.lng) < returnRadiusM(b, minOf(ab, bc))
  }

  /**
   * How close C must come back to A for B to be a spur. A spur that reports no
   * motion of its own may come back a share of its length away; production
   * (Oct 26) had 1,388 m out and 44 m back, 486 m out and 50 m back. One that
   * carries real speed keeps the fixed radius, so a genuine detour is kept.
   */
  private fun returnRadiusM(spur: Fix, shorterLegM: Double): Double =
    if (reportsNoMotion(spur)) max(ZIGZAG_RETURN_M, ZIGZAG_RETURN_SHARE * shorterLegM) else ZIGZAG_RETURN_M

  companion object {
    /** Implied-speed ceiling. Field staff ride bikes and cars; 126 km/h is generous. */
    const val MAX_SPEED_MPS = 35.0

    /** Fixes older than this (monotonic age) are cached replays, not the present. */
    const val MAX_FIX_AGE_MS = 60_000L

    /** Floor for dt in the speed calculation (batched deliveries share timestamps). */
    const val MIN_DT_MS = 1_000L

    /** Consecutive agreeing "teleport" fixes that move the anchor. */
    const val REANCHOR_COUNT = 3

    /** Agreement radius for the re-anchor candidate. */
    const val REANCHOR_RADIUS_M = 150.0

    /** Below this reported speed a fix claims to be stationary (GNSS jitter sits under it). */
    const val STILL_SPEED_MPS = 0.5

    /** A fix reporting at least this speed proves motion (brisk walk / any vehicle). */
    const val MOTION_SPEED_MPS = 2.0

    /** How long proven motion vouches for later speed-less fixes (network fixes mid-drive). */
    const val MOTION_MEMORY_MS = 60_000L

    /**
     * A stationary-claiming fix further than max(this, [UNCONFIRMED_ACC_FACTOR] x acc)
     * from the anchor needs confirmation. 75 m sits above normal GNSS wander and
     * below the 300-1000 m scatter seen in production.
     */
    const val UNCONFIRMED_MIN_JUMP_M = 75.0
    const val UNCONFIRMED_ACC_FACTOR = 3.0

    /** Parked points: one per this interval after the last written row. */
    const val SNAP_INTERVAL_MS = 10 * 60_000L

    /**
     * A parked fix within this of the last point is written as it is; a farther
     * one (indoor fixes stray 20-30 m) is replaced by the fix that stayed nearest.
     */
    const val PARKED_CLOSE_M = 3.0

    /** A→B and B→C must both exceed this for B to be a zig-zag spur... */
    const val ZIGZAG_LEG_M = 100.0

    /** ...while A→C stays within this. */
    const val ZIGZAG_RETURN_M = 40.0

    /** ...or, for a spur reporting no motion, within this share of its shorter leg. */
    const val ZIGZAG_RETURN_SHARE = 0.15

    /** Below this reported speed (on foot) a fix must stay within what its pace explains... */
    const val WALKING_PACE_MAX_MPS = 3.0
    /** ...which is this many times that pace over the time since the reference fix... */
    const val PACE_FACTOR = 2.0
    /** ...plus both fixes' accuracy and this margin... */
    const val PACE_MARGIN_M = 20.0
    /** ...judged only this soon after the reference fix: an older one says little about pace. */
    const val PACE_CHECK_MAX_DT_S = 180.0

    /** Far fixes needed before a trail heading away counts as travel... */
    const val TRAVEL_MIN_FIXES = 3
    /** ...gaining at least this much distance from the anchor... */
    const val TRAVEL_MIN_PROGRESS_M = 150.0
    /** ...each never falling back by more than this... */
    const val TRAVEL_NOISE_M = 25.0
    /** ...each within this of the newest fix's bearing from the anchor... */
    const val TRAVEL_BEARING_TOLERANCE_DEG = 45.0
    /** ...within this window, at most this many fixes kept... */
    const val TRAVEL_WINDOW_MS = 15 * 60_000L
    const val TRAVEL_MAX_FIXES = 12
    /** ...the newest beyond the reach of indoor scatter (seen up to ~900 m). */
    const val TRAVEL_MIN_DISTANCE_M = 1_200.0
    /** Far enough out for a trail to end still mode (scatter nights: ~2 such trails a night). */
    const val TRAVEL_SUSPECT_MIN_DISTANCE_M = 500.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
      val dLat = Math.toRadians(lat2 - lat1)
      val dLng = Math.toRadians(lng2 - lng1)
      val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
      return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** Initial bearing from the first point to the second, 0-360 degrees. */
    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
      val p1 = Math.toRadians(lat1)
      val p2 = Math.toRadians(lat2)
      val dLng = Math.toRadians(lng2 - lng1)
      val y = sin(dLng) * cos(p2)
      val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLng)
      return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest angle between two bearings, 0-180 degrees. */
    fun angleBetweenDeg(a: Double, b: Double): Double {
      val d = abs(a - b) % 360.0
      return if (d > 180.0) 360.0 - d else d
    }
  }
}
