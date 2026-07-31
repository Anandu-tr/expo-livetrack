package expo.modules.livetrack.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tolerant parser for the server's upload ACK.
 *
 * WHY THIS EXISTS: the wire contract documents `{"acceptedIds": ["<rowId>", ...]}`,
 * but real deployments wrap it (`{"success":true,"data":{...}}`), rename the field,
 * return objects instead of scalars, or answer 201 with no body at all. The strict
 * parser this replaces returned an empty list for every one of those shapes — and
 * the uploader's 2xx branch treated "zero acks" as complete success, so nothing was
 * ever deleted and the same batch re-uploaded forever with no diagnostic.
 *
 * SAFETY: this NEVER invents an id. An unrecognised shape yields an empty list,
 * which the caller now treats as a hard signal (diagnostic + attempt increment),
 * not as success. The caller additionally intersects the result with the ids it
 * actually sent, so a server echoing its OWN primary keys cannot delete an
 * unrelated buffered row by coincidence.
 *
 * Extracted from [UploadWorker] as a top-level object so it is unit-testable: a
 * CoroutineWorker cannot be instantiated in a plain JVM test, this can.
 */
internal object AckParser {

  /** Envelope containers searched after the root. First hit wins. */
  private val ENVELOPE_KEYS = listOf("data", "result")

  /** Field names searched inside each container, in order. */
  private val ARRAY_KEYS = listOf("acceptedIds", "acceptedIDs", "accepted", "ids")

  /** Keys read off an object-shaped array element, in order. */
  private val ELEMENT_ID_KEYS = listOf("id", "rowId", "rowid", "clientId")

  /**
   * Extract the acknowledged row ids from a response body.
   *
   * Accepts, in precedence order: a bare top-level array; a root-level field from
   * [ARRAY_KEYS]; the same fields nested under [ENVELOPE_KEYS]. Elements may be
   * strings, numbers, or objects carrying one of [ELEMENT_ID_KEYS]. Result is
   * de-duplicated, insertion-ordered, and empty for anything unparseable.
   */
  fun parse(body: String?): List<Long> {
    if (body.isNullOrBlank()) return emptyList()
    // runCatching, not a JSON check: proxies and load balancers answer with HTML
    // error pages, which must degrade to "no acks" rather than crash the worker.
    return runCatching {
      val trimmed = body.trim()
      if (trimmed.startsWith("[")) return@runCatching extract(JSONArray(trimmed))

      val root = JSONObject(trimmed)
      val arr = findArray(root) ?: return@runCatching emptyList()
      extract(arr)
    }.getOrDefault(emptyList())
  }

  /** Root fields first, then `data.*`, then `result.*`. */
  private fun findArray(root: JSONObject): JSONArray? {
    ARRAY_KEYS.forEach { key -> root.optJSONArray(key)?.let { return it } }
    ENVELOPE_KEYS.forEach { envelope ->
      val nested = root.optJSONObject(envelope) ?: return@forEach
      ARRAY_KEYS.forEach { key -> nested.optJSONArray(key)?.let { return it } }
    }
    return null
  }

  /** Scalars (String or Number) and objects carrying an id key. Junk is skipped. */
  private fun extract(arr: JSONArray): List<Long> {
    val out = LinkedHashSet<Long>()
    for (i in 0 until arr.length()) {
      when (val value = arr.opt(i)) {
        is JSONObject -> ELEMENT_ID_KEYS.firstNotNullOfOrNull { coerce(value.opt(it)) }?.let(out::add)
        else -> coerce(value)?.let(out::add)
      }
    }
    return out.toList()
  }

  private fun coerce(value: Any?): Long? = when (value) {
    null, JSONObject.NULL -> null
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
  }
}
