package expo.modules.livetrack.capture

/**
 * Per-window counters behind the periodic STATS heartbeat: what the location
 * provider delivered and what [LocationGate] did with it. Lets the server tell
 * "no fixes arrived" from "fixes arrived and were rejected", which the stored
 * points alone cannot (rejects are otherwise only logged locally).
 *
 * Not thread-safe; the service touches it from its main looper only.
 */
class GateStats {
  private var nDelivered = 0
  private var nAccepted = 0
  private var nSnaps = 0
  private var nProbes = 0
  private var fastMs = 0L
  /** One slot per known reason, plus a last slot for anything unrecognised. */
  private val rejects = IntArray(REASONS.size + 1)

  fun delivered() { nDelivered++ }
  fun accepted() { nAccepted++ }
  fun snap() { nSnaps++ }
  fun probe() { nProbes++ }

  /** Time spent with fast (vehicle-speed) fix requests. */
  fun fast(ms: Long) { if (ms > 0) fastMs += ms }

  fun reject(reason: String) {
    val i = REASONS.indexOfFirst { it.first == reason }
    rejects[if (i >= 0) i else REASONS.size]++
  }

  fun reset() {
    nDelivered = 0
    nAccepted = 0
    nSnaps = 0
    nProbes = 0
    fastMs = 0L
    rejects.fill(0)
  }

  /**
   * Compact `r` value for a HEARTBEAT row, e.g.
   * `STATS|m=S|ar=12|d=60|a=10|sn=2|acc=5|st=0|oo=0|tp=1|ji=40|zz=0|un=3|pr=1|fc=0`.
   * `m` = still (S) / moving (M); `ar` = minutes since the last Activity
   * Recognition result, -1 if none yet this run; `fc` = seconds of fast fix
   * requests (read against the rows' battery level).
   */
  fun format(still: Boolean, arSilentMin: Long?): String = buildString {
    append("STATS|m=").append(if (still) 'S' else 'M')
    append("|ar=").append(arSilentMin ?: -1L)
    append("|d=").append(nDelivered)
    append("|a=").append(nAccepted)
    append("|sn=").append(nSnaps)
    REASONS.forEachIndexed { i, (_, key) -> append('|').append(key).append('=').append(rejects[i]) }
    append("|pr=").append(nProbes)
    append("|fc=").append(fastMs / 1000)
    if (rejects[REASONS.size] > 0) append("|x=").append(rejects[REASONS.size])
  }

  private companion object {
    /** Gate reject reason -> short key, in output order. */
    val REASONS = listOf(
      "accuracy" to "acc",
      "stale" to "st",
      "out-of-order" to "oo",
      "teleport" to "tp",
      "jitter" to "ji",
      "zigzag" to "zz",
      "unconfirmed" to "un",
    )
  }
}
