package expo.modules.livetrack.capture

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A few seconds of accelerometer readings say whether the phone is being carried
 * or ridden: a phone lying on a desk barely moves the needle, a pocket on a
 * moving bike shakes it. The signal for phones whose own motion sources fail.
 * Production (Oct 29-30): Activity Recognition silent for up to 226 min
 * (c21a1207) or reading a ride as STILL for 6-9 min (ee6f4e92, c21a1207), and
 * the significant-motion trigger never fired on those rides, so the tracker sat
 * in still mode for up to 30 minutes of riding.
 *
 * Judged on the spread of the acceleration's magnitude, which does not depend
 * on how the phone lies. Pure; the service feeds it readings and times
 * (elapsedRealtime) from its main looper.
 */
class MotionCheck {

  /** The outcome of one check: [std] is the spread in m/s². */
  data class Verdict(val moving: Boolean, val std: Double)

  private var running = false
  private var lastStartAt: Long? = null
  private var n = 0
  private var sum = 0.0
  private var sumSq = 0.0

  private var lastVerdict: Verdict? = null
  private var lastVerdictAt = 0L

  /** A check may start: none running and none started in the last [GAP_MS]. */
  fun due(now: Long): Boolean = !running && (lastStartAt?.let { now - it >= GAP_MS } ?: true)

  fun start(now: Long) {
    running = true
    lastStartAt = now
    n = 0
    sum = 0.0
    sumSq = 0.0
  }

  /** One accelerometer reading (m/s², any orientation); ignored outside a check. */
  fun sample(x: Float, y: Float, z: Float) {
    if (!running) return
    val m = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)
    n++
    sum += m
    sumSq += m * m
  }

  /** End the running check: its verdict, or null when too few readings arrived to say. */
  fun finish(now: Long): Verdict? {
    if (!running) return null
    running = false
    if (n < MIN_READINGS) return null
    val mean = sum / n
    val std = sqrt(max(0.0, sumSq / n - mean * mean))
    return Verdict(std >= MOVING_STD, std).also {
      lastVerdict = it
      lastVerdictAt = now
    }
  }

  /** The latest verdict said moving, no longer than [TRUST_MS] ago. */
  fun movingRecently(now: Long): Boolean =
    lastVerdict?.moving == true && now - lastVerdictAt <= TRUST_MS

  companion object {
    /** Readings are taken for this long... */
    const val SAMPLE_MS = 3_000L

    /** ...and at least this many must arrive for a verdict. */
    const val MIN_READINGS = 20

    /**
     * Spread of the magnitude at or above which the phone is moving. A phone on
     * a desk reads ~0.02 (sensor noise), a pocket while sitting stays under ~0.3,
     * walking and two-wheelers read well above. STATS `ms` reports the largest
     * spread per window so this can be tuned from field data.
     */
    const val MOVING_STD = 0.4

    /** At most one check per this interval. */
    const val GAP_MS = 45_000L

    /** A moving verdict holds off still mode for this long. */
    const val TRUST_MS = 2 * 60_000L
  }
}
