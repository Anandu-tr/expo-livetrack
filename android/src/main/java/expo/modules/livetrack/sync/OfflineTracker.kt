package expo.modules.livetrack.sync

/**
 * Turns raw "has working internet" readings into internet off/on transitions.
 * Pure; the tracking service feeds it from its network callback and once a minute.
 *
 * Off is reported only after [confirmMs] of continuous no-internet (Wi-Fi /
 * mobile handovers drop it for seconds), timed from when the outage began. On is
 * reported as soon as internet is back after a reported Off. [recordedOnline]
 * is the last reported state, persisted by the caller across restarts.
 */
class OfflineTracker(recordedOnline: Boolean, private val confirmMs: Long) {

  sealed class Change {
    data class Off(val since: Long) : Change()
    data class On(val at: Long) : Change()
  }

  var recordedOnline: Boolean = recordedOnline
    private set

  /** Start of the current outage; null while online. */
  private var offlineSince: Long? = null

  fun update(online: Boolean, now: Long): Change? {
    if (online) {
      offlineSince = null
      if (recordedOnline) return null
      recordedOnline = true
      return Change.On(now)
    }
    val since = offlineSince ?: now.also { offlineSince = it }
    if (!recordedOnline || now - since < confirmMs) return null
    recordedOnline = false
    return Change.Off(since)
  }
}
