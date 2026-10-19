package expo.modules.livetrack.capture

import kotlin.math.asin
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
 *
 * Model: every accepted fix is HELD for one cycle so the next fix can vote on
 * whether it was a spur (zig-zag). The previously held fix is RELEASED when the
 * next one is accepted, so writes lag capture by one fix. [flush] releases the
 * held fix on demand (stop, still-transition).
 *
 * @param T opaque payload the caller wants back when the fix is released
 *          (Android passes the fully-built row so metadata captured at arrival
 *          time — battery, activity, mock — travels with the fix).
 */
class LocationGate<T>(
  private val movingDistanceM: Double,
  private val maxAccuracyM: Double,
  private val keepaliveMs: Long,
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
    /** Dropped. `reason` is one of accuracy|stale|out-of-order|teleport|jitter|unconfirmed|zigzag. */
    data class Reject(val reason: String) : Outcome<Nothing>()

    /** Accepted and held for the zig-zag lookahead; nothing to write yet. */
    data class Hold(val displacementM: Double) : Outcome<Nothing>()

    /** The previously held fix is released for writing; the new fix is now held. */
    data class Release<T>(val fix: Fix, val payload: T, val displacementM: Double) : Outcome<T>()

    /**
     * Stationary keepalive: the caller should write a synthetic row at the anchor
     * position with time [t] (speed 0). Emitted at most once per [keepaliveMs]
     * while the device stays within the jitter radius.
     */
    data class Snap(val lat: Double, val lng: Double, val t: Long) : Outcome<Nothing>()
  }

  private class Held<T>(val fix: Fix, val payload: T)

  /** Last fix released for writing. */
  private var emitted: Fix? = null

  /** Accepted fix awaiting the lookahead vote. */
  private var held: Held<T>? = null

  /** Monotonic time base for dt. Advances on accept and on every Snap. */
  private var anchorMonotonicT: Long = 0L

  // Re-anchor candidate, shared by the teleport and unconfirmed-jump gates: both
  // mean "far from the anchor", and agreeing fixes are the cure for either.
  private var cLat = 0.0
  private var cLng = 0.0
  private var cMonotonicT = 0L
  private var cCount = 0

  /** Monotonic time of the last fix whose own speed proved motion; null = none yet. */
  private var lastMotionMonotonicT: Long? = null

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

    if (d < max(movingDistanceM, fix.acc)) {
      cCount = 0
      if (dt >= keepaliveMs) {
        anchorMonotonicT = fix.monotonicT
        val h = held
        if (h != null) {
          // The held real fix doubles as the keepalive.
          held = null
          emitted = h.fix
          return Outcome.Release(h.fix, h.payload, d)
        }
        return Outcome.Snap(a.lat, a.lng, fix.t)
      }
      return Outcome.Reject("jitter")
    }

    if (isUnconfirmedJump(fix, d)) {
      return if (confirmCandidate(fix)) accept(fix, payload, d) else Outcome.Reject("unconfirmed")
    }
    cCount = 0

    return accept(fix, payload, d)
  }

  /**
   * A big jump from a fix that reports no motion of its own, with no recent fix
   * that did. A parked phone indoors produces exactly this: fixes claiming
   * speed 0 and 10 m accuracy while landing 300-1000 m away. The speed gate
   * can't catch them because its allowance grows with time since the last
   * accepted fix. Genuine moves either carry speed (GNSS) or repeat at the new
   * place, which [confirmCandidate] accepts.
   */
  private fun isUnconfirmedJump(fix: Fix, d: Double): Boolean {
    val reportsNoMotion = fix.speedMps == null || fix.speedMps < STILL_SPEED_MPS
    val recentMotion = lastMotionMonotonicT?.let { fix.monotonicT - it <= MOTION_MEMORY_MS } ?: false
    return reportsNoMotion && !recentMotion &&
      d > max(UNCONFIRMED_MIN_JUMP_M, UNCONFIRMED_ACC_FACTOR * fix.acc)
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
    if (cCount >= REANCHOR_COUNT) {
      cCount = 0
      return true
    }
    return false
  }

  /**
   * Release the held fix, if any, without waiting for a successor. Call on stop
   * and on the still-transition so the last real position is never stranded.
   */
  fun flush(): Pair<Fix, T>? {
    val h = held ?: return null
    held = null
    emitted = h.fix
    return h.fix to h.payload
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
      return Outcome.Release(h.fix, h.payload, d)
    }
    return Outcome.Hold(d)
  }

  private fun isZigZag(a: Fix, b: Fix, c: Fix): Boolean =
    haversineM(a.lat, a.lng, b.lat, b.lng) > ZIGZAG_LEG_M &&
      haversineM(b.lat, b.lng, c.lat, c.lng) > ZIGZAG_LEG_M &&
      haversineM(a.lat, a.lng, c.lat, c.lng) < ZIGZAG_RETURN_M

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

    /** A→B and B→C must both exceed this for B to be a zig-zag spur... */
    const val ZIGZAG_LEG_M = 100.0

    /** ...while A→C stays within this. */
    const val ZIGZAG_RETURN_M = 40.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
      val dLat = Math.toRadians(lat2 - lat1)
      val dLng = Math.toRadians(lng2 - lng1)
      val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
      return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }
  }
}
