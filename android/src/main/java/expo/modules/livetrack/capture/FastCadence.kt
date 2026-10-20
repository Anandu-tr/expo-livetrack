package expo.modules.livetrack.capture

/**
 * Decides when the moving location request asks for fast fixes: only while the
 * phone was recently at vehicle speed, where 10 s legs cut corners (Oct 2026:
 * city turns drawn as diagonals). Walking pace, stops and still mode keep the
 * normal cadence; faster requests there would only cost battery.
 *
 * Times are monotonic milliseconds. Pure; single-threaded by contract (the
 * service's main looper).
 */
class FastCadence(
  private val speedMps: Double = SPEED_MPS,
  private val holdMs: Long = HOLD_MS,
) {

  /** Whether fast fixes are in force. */
  var on = false
    private set

  /** Last usable fix at vehicle speed; null = none yet (0 is a valid time). */
  private var lastFastAt: Long? = null
  private var onSince = 0L
  private var spentMs = 0L

  /** Feed a usable fix's reported speed. True if [on] changed. */
  fun onSpeed(speed: Double, now: Long, still: Boolean): Boolean {
    if (speed >= speedMps) lastFastAt = now
    return update(now, still)
  }

  /** Re-evaluate on a tick or a still/moving transition. True if [on] changed. */
  fun update(now: Long, still: Boolean): Boolean {
    val want = !still && (lastFastAt?.let { now - it <= holdMs } ?: false)
    if (want == on) return false
    if (want) onSince = now else spentMs += now - onSince
    on = want
    return true
  }

  /** Time spent with fast fixes since the last call (the STATS `fc` field). */
  fun takeSpentMs(now: Long): Long {
    if (on) {
      spentMs += now - onSince
      onSince = now
    }
    return spentMs.also { spentMs = 0L }
  }

  companion object {
    /** ~11 km/h: above jogging, so walking stays on the normal cadence. */
    const val SPEED_MPS = 3.0

    /** Rides through traffic lights without dropping back. */
    const val HOLD_MS = 2 * 60_000L
  }
}
