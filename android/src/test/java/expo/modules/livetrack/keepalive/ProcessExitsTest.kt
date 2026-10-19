package expo.modules.livetrack.keepalive

import expo.modules.livetrack.keepalive.ProcessExits.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitsTest {

  private val fgs = 125 // RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
  private val cached = 400 // RunningAppProcessInfo.IMPORTANCE_CACHED

  @Test fun recordsOnlyExitsNewerThanTheWatermarkOldestFirst() {
    val exits = listOf(
      Exit(3_000, ProcessExits.REASON_OTHER, fgs, null),
      Exit(1_000, ProcessExits.REASON_LOW_MEMORY, fgs, null),
      Exit(2_000, ProcessExits.REASON_SIGNALED, fgs, null),
    )
    val (keep, mark) = ProcessExits.select(exits, watermark = 1_000)
    assertEquals(listOf(2_000L, 3_000L), keep.map { it.timestamp })
    assertEquals(3_000L, mark)
  }

  @Test fun forceStopIsAlwaysRecordedEvenWhenNotTracking() {
    val (keep, _) = ProcessExits.select(listOf(Exit(5_000, ProcessExits.REASON_USER_STOPPED, cached, null)), 0)
    assertEquals(1, keep.size)
  }

  @Test fun deathsOfAnIdleBackgroundProcessAreSkippedButAdvanceTheWatermark() {
    // After sign-out the process idles in the cache and is reclaimed: not a tracking loss.
    val (keep, mark) = ProcessExits.select(listOf(Exit(5_000, ProcessExits.REASON_OTHER, cached, null)), 0)
    assertTrue(keep.isEmpty())
    assertEquals(5_000L, mark)
  }

  @Test fun nothingNewKeepsTheWatermark() {
    val (keep, mark) = ProcessExits.select(listOf(Exit(500, ProcessExits.REASON_OTHER, fgs, null)), 1_000)
    assertTrue(keep.isEmpty())
    assertEquals(1_000L, mark)
  }

  @Test fun labelsNameTheCauseAndKeepAShortDescription() {
    assertEquals("force_stop", ProcessExits.label(ProcessExits.REASON_USER_STOPPED, null))
    assertEquals("killed_low_memory", ProcessExits.label(ProcessExits.REASON_LOW_MEMORY, "  "))
    assertEquals("killed_other:kill by process manager", ProcessExits.label(ProcessExits.REASON_OTHER, "kill  by\nprocess manager"))
    assertEquals("killed_unknown", ProcessExits.label(99, null))
    val long = ProcessExits.label(ProcessExits.REASON_OTHER, "x".repeat(200))
    assertEquals("killed_other:".length + ProcessExits.MAX_DESCRIPTION, long.length)
  }
}
