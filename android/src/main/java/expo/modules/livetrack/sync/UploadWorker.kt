package expo.modules.livetrack.sync

import android.content.Context
import android.os.Bundle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import expo.modules.livetrack.LiveTrackEventBus
import expo.modules.livetrack.Prefs
import expo.modules.livetrack.diagnostics.DiagnosticsReporter
import expo.modules.livetrack.diagnostics.DiagnosticsReporters
import expo.modules.livetrack.buffer.BufferDb
import expo.modules.livetrack.buffer.PointDao
import expo.modules.livetrack.buffer.PointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Flushes buffered rows to the server (the wire contract mirrored by the
 * in-package test-server).
 *
 * Body:
 * ```json
 * { "points": [ {"id","u","l","g","t","s","acc","b","c","act","mock"} ],
 *   "events": [ {"id","u","e","t","l?","g?","b?"} ] }
 * ```
 * A buffer row is a location when `eventType == null` (goes in `points`), else an
 * event (goes in `events`, `e = eventType`). Each `id` is the Room row id as a
 * String. Response 2xx: `{ "acceptedIds": ["<rowId>", ...] }` (see [AckParser] for
 * the envelope/field variants tolerated); only rows whose ids come back are
 * deleted — the rest are retried by the next run, up to [DEFAULT_MAX_UPLOAD_ATTEMPTS].
 *
 * HttpURLConnection is used (not OkHttp) to avoid relying on a transitive dep we
 * cannot confirm here. // VERIFY: HttpURLConnection is always on Android; fine.
 */
class UploadWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val url = prefs.getString(Prefs.KEY_URL, null)
    var token = prefs.getString(Prefs.KEY_TOKEN, null)
    // Clamp on READ as well as on write: `batchSize` is host-supplied and older
    // builds persisted it unclamped, so an oversized value could still be sitting
    // in prefs. An oversized IN (:ids) list throws "too many SQL variables".
    val batchSize = prefs.getInt(Prefs.KEY_BATCH_SIZE, 50).coerceIn(1, SQL_VAR_CHUNK)
    val maxAttempts = prefs.getInt(Prefs.KEY_MAX_UPLOAD_ATTEMPTS, DEFAULT_MAX_UPLOAD_ATTEMPTS)
      .coerceAtLeast(1)
    val reporter = DiagnosticsReporters.resolve(prefs)
    val provider = loadProvider(prefs)

    if (url.isNullOrBlank()) {
      // Not configured (yet) — nothing we can do; don't retry forever.
      return@withContext Result.success()
    }

    // Proactive refresh: ask the provider for a current token before posting. The
    // provider returns a still-valid cached token cheaply, or mints a fresh one if
    // the previous has expired — so this works even when the app's JS is dead.
    if (provider != null) {
      val fresh = runCatching { provider.freshToken(false) }.getOrNull()
      if (fresh != null) {
        token = fresh
        prefs.edit().putString(Prefs.KEY_TOKEN, fresh).apply()
      } else {
        reporter.recordFailure("token-provider", "freshToken(false) returned null")
      }
    }

    if (token.isNullOrBlank()) {
      return@withContext Result.success()
    }

    val dao: PointDao = BufferDb.getInstance(applicationContext).pointDao()

    val rows = runCatching { dao.unsynced(batchSize) }.getOrElse {
      reporter.recordFailure(
        "db-read", it.message ?: "unsynced failed",
        mapOf("batchSize" to batchSize.toString()),
      )
      // DB read failure — let WorkManager back off and retry.
      return@withContext Result.retry()
    }
    if (rows.isEmpty()) return@withContext Result.success()

    val body = buildBody(rows)

    var outcome = try {
      post(url, token, body)
    } catch (e: Exception) {
      reporter.recordFailure(
        "upload-network", e.message ?: "network error",
        mapOf("bufferedCount" to (runCatching { dao.count() }.getOrDefault(-1)).toString()),
      )
      // Network/transport failure — emit best-effort error + retry with backoff.
      emitSyncError(e.message ?: "network error", null, dao)
      return@withContext Result.retry()
    }

    // Reactive refresh: a single 401 -> force a fresh token -> retry the batch once.
    if (outcome.code == 401 && provider != null) {
      val fresh = runCatching { provider.freshToken(true) }.getOrNull()
      if (fresh != null) {
        token = fresh
        prefs.edit().putString(Prefs.KEY_TOKEN, fresh).apply()
        outcome = try {
          post(url, token, body)
        } catch (e: Exception) {
          reporter.recordFailure("upload-network", e.message ?: "network error after refresh")
          emitSyncError(e.message ?: "network error", null, dao)
          return@withContext Result.retry()
        }
      } else {
        reporter.recordFailure("token-provider", "freshToken(true) returned null")
      }
    }

    when {
      outcome.code in 200..299 ->
        handleAccepted(outcome, rows.map { it.id }, dao, reporter, url, maxAttempts)
      // 401/403 and other non-retryable client errors: stop retrying.
      outcome.code == 401 || outcome.code == 403 || outcome.code in 400..499 -> {
        reporter.recordFailure(
          "upload-http", "server rejected",
          mapOf("status" to outcome.code.toString(), "host" to host(url)),
        )
        emitSyncError("server rejected (${outcome.code})", outcome.code, dao)
        Result.failure()
      }
      // 5xx and anything else: transient — retry with backoff.
      else -> {
        reporter.recordFailure(
          "upload-http", "server error",
          mapOf("status" to outcome.code.toString(), "host" to host(url)),
        )
        emitSyncError("server error (${outcome.code})", outcome.code, dao)
        Result.retry()
      }
    }
  }

  // --- Token provider -----------------------------------------------------

  /**
   * Reflectively load the app-supplied [TokenProvider] named in config. Cached
   * process-wide so we don't re-instantiate per run. Returns null if unconfigured
   * or the class can't be loaded (then we fall back to the stored static token).
   */
  private fun loadProvider(prefs: android.content.SharedPreferences): TokenProvider? {
    val className = prefs.getString(Prefs.KEY_TOKEN_PROVIDER_CLASS, null) ?: return null
    cachedProvider?.let { if (it::class.java.name == className) return it }
    return runCatching {
      (Class.forName(className).getDeclaredConstructor().newInstance() as TokenProvider)
    }.getOrNull()?.also { cachedProvider = it }
  }

  private fun host(u: String): String = runCatching { URL(u).host }.getOrDefault("?")

  // --- Body building ------------------------------------------------------

  private fun buildBody(rows: List<PointEntity>): String {
    val points = JSONArray()
    val events = JSONArray()

    for (r in rows) {
      if (r.eventType == null) {
        points.put(
          JSONObject().apply {
            put("id", r.id.toString())
            put("u", r.userId)
            put("l", r.lat ?: JSONObject.NULL)
            put("g", r.lng ?: JSONObject.NULL)
            put("t", r.t)
            put("s", r.speed)
            put("acc", r.acc)
            put("b", r.batt)
            put("c", r.charging)
            put("act", r.act ?: "UNKNOWN")
            put("mock", r.mock)
          },
        )
      } else {
        events.put(
          JSONObject().apply {
            put("id", r.id.toString())
            put("u", r.userId)
            put("e", r.eventType)
            put("t", r.t)
            r.lat?.let { put("l", it) }
            r.lng?.let { put("g", it) }
            if (r.batt >= 0) put("b", r.batt)
          },
        )
      }
    }

    return JSONObject().apply {
      put("points", points)
      put("events", events)
    }.toString()
  }

  // --- ACK handling -------------------------------------------------------

  /**
   * Classify a 2xx response and reconcile the buffer against it.
   *
   * WHY THIS EXISTS: a 2xx that acknowledged NOTHING used to be treated as full
   * success — no delete, no counter, no signal — so devices re-POSTed the same 50
   * rows indefinitely with zero observability (observed in production: the same
   * batch every 40 s–7 min for hours, across two independent devices). Now every
   * 2xx is classified: acked rows are deleted, unacked rows are counted, a zero-ack
   * response is always reported with a body snippet (so the real server shape
   * becomes visible), and rows that exhaust their attempts are evicted and reported.
   */
  private fun handleAccepted(
    outcome: HttpOutcome,
    sentIds: List<Long>,
    dao: PointDao,
    reporter: DiagnosticsReporter,
    url: String,
    maxAttempts: Int,
  ): Result {
    val parsed = AckParser.parse(outcome.body)
    val sentSet = sentIds.toSet()
    // Only ever delete ids we actually sent. A server echoing its OWN primary keys
    // instead of our row ids must not be able to delete an unrelated buffered row
    // by coincidence — that would be silent data loss.
    val accepted = parsed.filter { it in sentSet }
    val acceptedSet = accepted.toSet()
    val unacked = sentIds.filterNot { it in acceptedSet }

    accepted.chunked(SQL_VAR_CHUNK).forEach { chunk ->
      runCatching { dao.deleteByIds(chunk) }.onFailure {
        reporter.recordFailure(
          "db-write", it.message ?: "deleteByIds failed",
          mapOf("idsCount" to chunk.size.toString()),
        )
      }
    }

    if (accepted.isEmpty()) {
      // Fires on attempt #1, hours before any eviction — this is the missing signal
      // that let the production loop run unnoticed.
      reporter.recordFailure(
        if (parsed.isEmpty()) "upload-ack-empty" else "upload-ack-foreign",
        if (parsed.isEmpty()) "2xx with zero parseable acceptedIds"
        else "2xx acked ${parsed.size} ids, none of them ours",
        mapOf(
          "status" to outcome.code.toString(),
          "host" to host(url),
          "rowsSent" to sentIds.size.toString(),
          "parsedCount" to parsed.size.toString(),
          "sampleSent" to sentIds.take(3).joinToString(","),
          "sampleParsed" to parsed.take(3).joinToString(","),
          "bodyLen" to (outcome.body?.length ?: 0).toString(),
          "bodySnippet" to snippet(outcome.body),
          "bufferedCount" to (runCatching { dao.count() }.getOrDefault(-1)).toString(),
        ),
      )
    }

    if (unacked.isNotEmpty()) {
      unacked.chunked(SQL_VAR_CHUNK).forEach { chunk ->
        runCatching { dao.incrementAttempts(chunk) }.onFailure {
          reporter.recordFailure(
            "db-write", it.message ?: "incrementAttempts failed",
            mapOf("idsCount" to chunk.size.toString()),
          )
        }
      }
      evictExhausted(dao, reporter, maxAttempts)
    }

    // Zero acks -> hand back to WorkManager's exponential backoff instead of
    // success(), so a server that 2xx's while acknowledging nothing cannot drive a
    // hot per-fix retry loop (that loop was the observed battery cost).
    // Partial/full acks -> success(): real progress was made, let the next nudge run.
    return if (accepted.isEmpty()) Result.retry() else Result.success()
  }

  /**
   * Bounded, logged eviction — the ONLY path that can drop a buffered row without a
   * server ACK. Reached only for rows the server has affirmatively declined
   * [maxAttempts] separate times under a 2xx.
   */
  private fun evictExhausted(dao: PointDao, reporter: DiagnosticsReporter, maxAttempts: Int) {
    val sample = runCatching { dao.idsExceedingAttempts(maxAttempts, 20) }.getOrDefault(emptyList())
    if (sample.isEmpty()) return
    val oldestT = runCatching { dao.oldestExceedingAttempts(maxAttempts) }.getOrNull()
    val evicted = runCatching { dao.deleteExceedingAttempts(maxAttempts) }.getOrElse {
      reporter.recordFailure("db-write", it.message ?: "deleteExceedingAttempts failed")
      return
    }
    if (evicted <= 0) return
    reporter.recordFailure(
      "buffer-evicted",
      "dropped $evicted rows after $maxAttempts unacknowledged 2xx uploads",
      mapOf(
        "evictedCount" to evicted.toString(),
        "maxAttempts" to maxAttempts.toString(),
        "sampleIds" to sample.take(10).joinToString(","),
        "oldestT" to (oldestT?.toString() ?: "?"),
      ),
    )
    emitSyncError(
      "dropped $evicted buffered rows after $maxAttempts failed uploads", null, dao, evicted,
    )
  }

  /**
   * Collapsed + truncated: an HTML error page from a proxy would otherwise blow the
   * crash reporter's per-key size limit.
   */
  private fun snippet(body: String?): String =
    (body ?: "").replace(WHITESPACE, " ").trim().take(BODY_SNIPPET_MAX)

  // --- Networking ---------------------------------------------------------

  private data class HttpOutcome(val code: Int, val body: String?)

  private fun post(url: String, token: String, jsonBody: String): HttpOutcome {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Authorization", "Bearer $token")
    }
    try {
      conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
      return HttpOutcome(code, text)
    } finally {
      conn.disconnect()
    }
  }

  // --- Error emission -----------------------------------------------------

  private fun emitSyncError(message: String, status: Int?, dao: PointDao, droppedCount: Int? = null) {
    val bufferedCount = runCatching { dao.count() }.getOrDefault(-1)
    val payload = Bundle().apply {
      putString("message", message)
      status?.let { putInt("status", it) }
      putInt("bufferedCount", bufferedCount)
      droppedCount?.let { putInt("droppedCount", it) }
    }
    // Best-effort: dropped if no JS module is attached (data is still buffered).
    runCatching { LiveTrackEventBus.emit(LiveTrackEventBus.EVENT_SYNC_ERROR, payload) }
  }

  companion object {
    private const val PREFS = "livetrack_prefs"
    private const val TIMEOUT_MS = 30_000

    /**
     * Attempts a row may sit unacknowledged under repeated 2xx responses before it
     * is evicted. See `PointEntity.attempts`.
     *
     * Attempts increment ONLY on "server returned 2xx and named none of your rows"
     * — network errors, 4xx and 5xx keep their retry()/failure() paths and never
     * touch the counter. Combined with the Result.retry() backoff below, the 15-min
     * periodic flush becomes the dominant driver, putting 15 attempts at roughly
     * 3-4 hours of continuously-confirmed refusal — while the diagnostic fires on
     * attempt #1. Overridable via `cadence.maxUploadAttempts`.
     */
    const val DEFAULT_MAX_UPLOAD_ATTEMPTS = 15

    /** Conservative bound for `IN (:ids)`: SQLITE_MAX_VARIABLE_NUMBER is 999 pre-3.32. */
    private const val SQL_VAR_CHUNK = 500

    /** Keeps a diagnostic body snippet under the crash reporter's per-key limit. */
    private const val BODY_SNIPPET_MAX = 300

    private val WHITESPACE = Regex("\\s+")

    /** Reflectively-loaded token provider, cached process-wide. */
    @Volatile
    private var cachedProvider: TokenProvider? = null

    private const val UNIQUE_ONE_TIME = "livetrack_upload"
    private const val UNIQUE_PERIODIC = "livetrack_upload_periodic"

    private val networkConstraints =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Enqueue a one-shot flush. Unique + KEEP so repeated nudges from the capture
     * path don't pile up duplicate work.
     *
     * NOTE: deliberately NOT expedited. Expedited CoroutineWorker on API < 31 runs
     * as a foreground service and requires overriding getForegroundInfo() (the
     * default throws). Promptness is already covered by the per-fix nudge plus the
     * 15-min periodic safety-net, so a normal request avoids that footgun.
     */
    fun enqueue(context: Context) {
      val request = OneTimeWorkRequestBuilder<UploadWorker>()
        .setConstraints(networkConstraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(UNIQUE_ONE_TIME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Periodic safety-net flush (~15 min, WorkManager's minimum period). KEEP so
     * re-scheduling on every start() is idempotent.
     */
    fun enqueuePeriodic(context: Context) {
      val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
        .setConstraints(networkConstraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_PERIODIC,
        // VERIFY: ExistingPeriodicWorkPolicy.KEEP (UPDATE exists on newer versions).
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
    }

    /** Cancel the periodic safety-net (one-shot work is left to drain). */
    fun cancelPeriodic(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
    }
  }
}
