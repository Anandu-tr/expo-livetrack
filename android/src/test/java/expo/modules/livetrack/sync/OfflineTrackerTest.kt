package expo.modules.livetrack.sync

import expo.modules.livetrack.sync.OfflineTracker.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineTrackerTest {

  private val confirm = 120_000L

  @Test fun briefDropDuringAHandoverRecordsNothing() {
    val t = OfflineTracker(recordedOnline = true, confirmMs = confirm)
    assertNull(t.update(online = false, now = 0))
    assertNull(t.update(online = false, now = 30_000))
    assertNull(t.update(online = true, now = 45_000))
    assertNull(t.update(online = false, now = 200_000)) // a new outage starts the clock again
  }

  @Test fun sustainedOutageIsRecordedOnceFromWhenItStarted() {
    val t = OfflineTracker(recordedOnline = true, confirmMs = confirm)
    assertNull(t.update(online = false, now = 10_000))
    assertEquals(Change.Off(since = 10_000), t.update(online = false, now = 130_000))
    assertNull(t.update(online = false, now = 600_000))
  }

  @Test fun internetBackAfterARecordedOutageIsRecordedImmediately() {
    val t = OfflineTracker(recordedOnline = true, confirmMs = confirm)
    t.update(online = false, now = 0)
    t.update(online = false, now = 200_000)
    assertEquals(Change.On(at = 210_000), t.update(online = true, now = 210_000))
    assertNull(t.update(online = true, now = 220_000))
  }

  @Test fun outageRecordedBeforeARestartEndsWhenInternetIsSeenAgain() {
    // Persisted state says the last recorded transition was NET_OFF.
    val t = OfflineTracker(recordedOnline = false, confirmMs = confirm)
    assertEquals(Change.On(at = 5_000), t.update(online = true, now = 5_000))
  }
}
