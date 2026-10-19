package expo.modules.livetrack.keepalive

/**
 * Rolling 24 h record of service starts that recovered from a silent process
 * death (watchdog, sticky restart, Activity Recognition restart). Stored in
 * prefs as comma-separated epoch ms; pure so it can be unit-tested. Feeds
 * `getState().killRestarts24h`, which the host app uses to decide when to show
 * its OEM battery-saver guidance again.
 */
object KillStats {
  const val WINDOW_MS = 24 * 60 * 60 * 1000L
  const val MAX_ENTRIES = 100

  private val KILL_REASONS = setOf("watchdog", "sticky_restart", "ar_restart")

  /** Start reasons that only happen because the process had died. */
  fun isKillRecovery(reason: String): Boolean = reason in KILL_REASONS

  /** [serialized] plus a restart at [now], pruned to the window and capped. */
  fun record(serialized: String?, now: Long): String =
    (parse(serialized, now) + now).sorted().takeLast(MAX_ENTRIES).joinToString(",")

  fun count(serialized: String?, now: Long): Int = parse(serialized, now).size

  private fun parse(serialized: String?, now: Long): List<Long> =
    serialized.orEmpty().split(',')
      .mapNotNull { it.trim().toLongOrNull() }
      .filter { now - it in 0..WINDOW_MS }
}
