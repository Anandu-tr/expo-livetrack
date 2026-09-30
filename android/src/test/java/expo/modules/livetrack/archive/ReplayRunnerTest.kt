package expo.modules.livetrack.archive

import expo.modules.livetrack.buffer.ArchivedPointEntity
import expo.modules.livetrack.sync.HttpOutcome
import expo.modules.livetrack.sync.TokenProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric because `UploadClient.buildBody` uses `org.json`. */
@RunWith(RobolectricTestRunner::class)
class ReplayRunnerTest {

  private fun rows(n: Int) = (1..n).map {
    ArchivedPointEntity(it.toLong(), "u1", it * 1000L, 9.0, 76.0, 1.0, 8.0, 50, false, null, false, 0)
  }

  private fun ackAll(body: String): String {
    // Echo every id in the request back as accepted.
    val ids = Regex("\"id\":\"(\\d+)\"").findAll(body).map { it.groupValues[1] }.toList()
    return "{\"acceptedIds\":[" + ids.joinToString(",") { "\"$it\"" } + "]}"
  }

  @Test fun batchesBySizeAndCountsAcceptedIds() = runTest {
    val posted = ArrayList<String>()
    val out = ReplayRunner.run(
      rows(7), "http://x", "tok", batchSize = 3, provider = null,
      poster = { _, _, body -> posted += body; HttpOutcome(200, ackAll(body)) },
    )
    assertEquals(3, posted.size)
    assertEquals(7, out.sent)
    assertEquals(7, out.accepted)
    assertEquals(3, out.batches)
    assertEquals(0, out.failedBatches)
  }

  @Test fun acceptedCountsOnlyIdsInTheChunk() = runTest {
    val out = ReplayRunner.run(
      rows(2), "http://x", "tok", batchSize = 10, provider = null,
      poster = { _, _, _ -> HttpOutcome(200, "{\"acceptedIds\":[\"1\",\"999\"]}") },
    )
    assertEquals(1, out.accepted)
  }

  @Test fun unauthorizedRefreshesOnceThenRetries() = runTest {
    val tokens = ArrayList<String>()
    var refreshed: String? = null
    val provider = object : TokenProvider {
      override fun freshToken(forceRefresh: Boolean): String = "fresh"
    }
    val out = ReplayRunner.run(
      rows(1), "http://x", "stale", batchSize = 10, provider = provider,
      poster = { _, token, body ->
        tokens += token
        if (token == "stale") HttpOutcome(401, null) else HttpOutcome(200, ackAll(body))
      },
      onTokenRefreshed = { refreshed = it },
    )
    assertEquals(listOf("stale", "fresh"), tokens)
    assertEquals("fresh", refreshed)
    assertEquals(1, out.accepted)
  }

  @Test fun unauthorizedWithoutProviderThrowsAuth() = runTest {
    try {
      ReplayRunner.run(
        rows(1), "http://x", "tok", batchSize = 10, provider = null,
        poster = { _, _, _ -> HttpOutcome(401, null) },
      )
      fail("expected AuthException")
    } catch (e: ReplayRunner.AuthException) {
      // expected
    }
  }

  @Test fun stopsAfterThreeConsecutiveFailures() = runTest {
    var calls = 0
    val out = ReplayRunner.run(
      rows(10), "http://x", "tok", batchSize = 1, provider = null,
      poster = { _, _, _ -> calls++; HttpOutcome(500, null) },
    )
    assertEquals(3, calls)
    assertEquals(3, out.failedBatches)
    assertEquals(3, out.batches)
    assertEquals(0, out.accepted)
  }

  @Test fun concurrentReplayIsRejectedAsBusy() = runTest {
    var sawBusy = false
    ReplayRunner.run(
      rows(1), "http://x", "tok", batchSize = 10, provider = null,
      poster = { _, _, body ->
        // Re-enter while the first run holds the guard.
        try {
          runBlocking {
            ReplayRunner.run(rows(1), "http://x", "tok", 10, null, poster = { _, _, _ -> HttpOutcome(200, "{}") })
          }
        } catch (e: ReplayRunner.BusyException) {
          sawBusy = true
        }
        HttpOutcome(200, ackAll(body))
      },
    )
    assertTrue(sawBusy)
    // Guard released afterwards.
    val again = ReplayRunner.run(rows(1), "http://x", "tok", 10, null, poster = { _, _, b -> HttpOutcome(200, ackAll(b)) })
    assertEquals(1, again.accepted)
  }
}
