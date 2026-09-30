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
   */
  data class Fix(val lat: Double, val lng: Double, val acc: Double, val t: Long, val monotonicT: Long)

  sealed class Outcome<out T> {
    /** Dropped. `reason` is one of accuracy|stale|out-of-order|teleport|jitter|zigzag. */
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

  // Re-anchor candidate for the teleport gate.
  private var cLat = 0.0
  private var cLng = 0.0
  private var cMonotonicT = 0L
  private var cCount = 0

  /** The fix everything is measured against: the held one if any, else the last released. */
  private val anchor: Fix?
    get() = held?.fix ?: emitted

  fun evaluate(fix: Fix, ageMs: Long, payload: T): Outcome<T> {
    if (fix.acc.isNaN() || fix.acc < 0.0 || fix.acc > maxAccuracyM) return Outcome.Reject("accuracy")
    if (ageMs > MAX_FIX_AGE_MS) return Outcome.Reject("stale")

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
        return accept(fix, payload, d)
      }
      return Outcome.Reject("teleport")
    }
    cCount = 0

    if (d < max(movingDistanceM, fix.acc)) {
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

    return accept(fix, payload, d)
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
