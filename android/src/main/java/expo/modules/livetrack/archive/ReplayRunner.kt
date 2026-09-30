package expo.modules.livetrack.archive

import expo.modules.livetrack.buffer.ArchivedPointEntity
import expo.modules.livetrack.sync.AckParser
import expo.modules.livetrack.sync.HttpOutcome
import expo.modules.livetrack.sync.TokenProvider
import expo.modules.livetrack.sync.UploadClient
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** Test seam over [UploadClient.post]. */
internal fun interface Poster {
  fun post(url: String, token: String, body: String): HttpOutcome
}

/**
 * Re-sends archived rows to the ingest URL with their ORIGINAL ids and times.
 *
 * Never touches the `points` buffer: a replay row that got a new id would be a
 * duplicate on the server. Paced and bounded so a user tapping "Resync" cannot
 * hammer the backend, and guarded so only one replay runs at a time.
 */
internal object ReplayRunner {
  data class Outcome(val sent: Int, val accepted: Int, val batches: Int, val failedBatches: Int)

  class AuthException : Exception("ingest returned 401 and no fresh token was available")
  class BusyException : Exception("a replay is already in progress")

  const val BATCH_PACING_MS = 250L
  const val MAX_CONSECUTIVE_FAILURES = 3

  private val inFlight = AtomicBoolean(false)

  suspend fun run(
    rows: List<ArchivedPointEntity>,
    url: String,
    initialToken: String,
    batchSize: Int,
    provider: TokenProvider?,
    poster: Poster = Poster { u, t, b -> UploadClient.post(u, t, b) },
    onTokenRefreshed: (String) -> Unit = {},
  ): Outcome {
    if (!inFlight.compareAndSet(false, true)) throw BusyException()
    try {
      var token = initialToken
      var refreshed = false
      var accepted = 0
      var batches = 0
      var failed = 0
      var consecutive = 0

      for (chunk in rows.chunked(batchSize.coerceIn(1, 500))) {
        batches++
        val body = UploadClient.buildBody(chunk.map { it.toPoint() })
        var out = runCatching { poster.post(url, token, body) }.getOrNull()

        if (out?.code == 401 && !refreshed) {
          val fresh = provider?.let { p -> runCatching { p.freshToken(true) }.getOrNull() }
            ?: throw AuthException()
          token = fresh
          refreshed = true
          onTokenRefreshed(fresh)
          out = runCatching { poster.post(url, token, body) }.getOrNull()
        }
        if (out?.code == 401) throw AuthException()

        if (out != null && out.code in 200..299) {
          val ids = chunk.map { it.id }.toSet()
          accepted += AckParser.parse(out.body).count { it in ids }
          consecutive = 0
        } else {
          failed++
          if (++consecutive >= MAX_CONSECUTIVE_FAILURES) break
        }
        delay(BATCH_PACING_MS)
      }
      return Outcome(rows.size, accepted, batches, failed)
    } finally {
      inFlight.set(false)
    }
  }
}
