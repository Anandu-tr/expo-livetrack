package expo.modules.livetrack.sync

import expo.modules.livetrack.buffer.PointEntity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class HttpOutcome(val code: Int, val body: String?)

/**
 * Stateless wire layer shared by [UploadWorker] (live buffer flush) and
 * `ReplayRunner` (archive re-send). Extracted from the worker so the replay path
 * posts byte-identical bodies without duplicating the JSON shape.
 *
 * HttpURLConnection is used (not OkHttp) to avoid relying on a transitive dep.
 */
internal object UploadClient {
  const val TIMEOUT_MS = 30_000

  /**
   * Body: `{ "points": [...], "events": [...] }`. A row is a location when
   * `eventType == null`, else an event with `e = eventType`. Ids are the row ids
   * as Strings — for archived rows these are the ORIGINAL ids, which is what
   * makes a replay idempotent server-side.
   */
  fun buildBody(rows: List<PointEntity>): String {
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
            // Lifecycle events carry their reason (boot/watchdog/...) in `act`.
            r.act?.let { put("r", it) }
          },
        )
      }
    }

    return JSONObject().apply {
      put("points", points)
      put("events", events)
    }.toString()
  }

  fun post(url: String, token: String, jsonBody: String): HttpOutcome {
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
}
