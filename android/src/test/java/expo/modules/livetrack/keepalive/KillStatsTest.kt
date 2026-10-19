package expo.modules.livetrack.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KillStatsTest {

  private val now = 1_792_000_000_000L
  private val hour = 3_600_000L

  @Test fun countsRecordedRestartsInsideTheWindow() {
    var s: String? = null
    s = KillStats.record(s, now - 3 * hour)
    s = KillStats.record(s, now - 2 * hour)
    s = KillStats.record(s, now - 1 * hour)
    assertEquals(3, KillStats.count(s, now))
  }

  @Test fun dropsEntriesOlderThanTheWindow() {
    var s: String? = null
    s = KillStats.record(s, now - 30 * hour)
    s = KillStats.record(s, now - 1 * hour)
    assertEquals(1, KillStats.count(s, now))
    // Recording also prunes, so the stored string doesn't grow forever.
    assertFalse(KillStats.record(s, now).contains((now - 30 * hour).toString()))
  }

  @Test fun storageIsCapped() {
    var s: String? = null
    val n = KillStats.MAX_ENTRIES + 20
    // In time order, as real restarts are recorded.
    repeat(n) { s = KillStats.record(s, now - (n - it) * 1000L) }
    assertEquals(KillStats.MAX_ENTRIES, KillStats.count(s, now))
  }

  @Test fun garbageIsIgnored() {
    assertEquals(0, KillStats.count(null, now))
    assertEquals(0, KillStats.count("", now))
    assertEquals(1, KillStats.count("abc,${now - hour},,x", now))
  }

  @Test fun onlyRecoveryReasonsCountAsKills() {
    assertTrue(KillStats.isKillRecovery("watchdog"))
    assertTrue(KillStats.isKillRecovery("sticky_restart"))
    assertTrue(KillStats.isKillRecovery("ar_restart"))
    assertFalse(KillStats.isKillRecovery("manual"))
    assertFalse(KillStats.isKillRecovery("boot"))
  }
}
