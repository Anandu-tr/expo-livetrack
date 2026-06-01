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
 * String. Response 200: `{ "acceptedIds": ["<rowId>", ...] }`; only rows whose
 * ids come back are deleted — the rest are retried by the next run.
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
    val url = prefs.getString("url", null)
    val token = prefs.getString("token", null)
    val batchSize = prefs.getInt("batchSize", 50)

    if (url.isNullOrBlank() || token.isNullOrBlank()) {
      // Not configured (yet) — nothing we can do; don't retry forever.
      return@withContext Result.success()
    }

    val dao: PointDao = BufferDb.getInstance(applicationContext).pointDao()

    val rows = runCatching { dao.unsynced(batchSize) }.getOrElse {
      // DB read failure — let WorkManager back off and retry.
      return@withContext Result.retry()
    }
    if (rows.isEmpty()) return@withContext Result.success()

    val body = buildBody(rows)

    val outcome = try {
      post(url, token, body)
    } catch (e: Exception) {
      // Network/transport failure — emit best-effort error + retry with backoff.
      emitSyncError(e.message ?: "network error", null, dao)
      return@withContext Result.retry()
    }

    when {
      outcome.code in 200..299 -> {
        val acceptedIds = parseAcceptedIds(outcome.body)
        if (acceptedIds.isNotEmpty()) {
          runCatching { dao.deleteByIds(acceptedIds) }
        }
        Result.success()
      }
      // 401/403 and other non-retryable client errors: stop retrying.
      outcome.code == 401 || outcome.code == 403 || outcome.code in 400..499 -> {
        emitSyncError("server rejected (${outcome.code})", outcome.code, dao)
        Result.failure()
      }
      // 5xx and anything else: transient — retry with backoff.
      else -> {
        emitSyncError("server error (${outcome.code})", outcome.code, dao)
        Result.retry()
      }
    }
  }

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

  private fun parseAcceptedIds(body: String?): List<Long> {
    if (body.isNullOrBlank()) return emptyList()
    return runCatching {
      val arr = JSONObject(body).optJSONArray("acceptedIds") ?: JSONArray()
      buildList {
        for (i in 0 until arr.length()) {
          // ids are sent as Strings; tolerate numbers too.
          arr.optString(i, null)?.toLongOrNull()?.let { add(it) }
        }
      }
    }.getOrDefault(emptyList())
  }

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

  private fun emitSyncError(message: String, status: Int?, dao: PointDao) {
    val bufferedCount = runCatching { dao.count() }.getOrDefault(-1)
    val payload = Bundle().apply {
      putString("message", message)
      status?.let { putInt("status", it) }
      putInt("bufferedCount", bufferedCount)
    }
    // Best-effort: dropped if no JS module is attached (data is still buffered).
    runCatching { LiveTrackEventBus.emit(LiveTrackEventBus.EVENT_SYNC_ERROR, payload) }
  }

  companion object {
    private const val PREFS = "livetrack_prefs"
    private const val TIMEOUT_MS = 30_000

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
