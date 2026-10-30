package expo.modules.livetrack.capture

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Which rejected fixes are uploaded as `REJ` HEARTBEAT rows, and their `r` text,
 * e.g. `REJ|un|l=9.97654|g=76.28765|a=24|s=0.0` (reason, position, accuracy in
 * m, reported speed in m/s or `-` for none; no commas, like STATS).
 *
 * Rejected fixes never reached the server, so the straight lines in production
 * (Oct 2026) could only be explained from counters, and the travel and vehicle
 * rules could only be tested on made-up fixes. Kept: the far rejections behind
 * those lines, and while moving the blurry fixes a GNSS-less ride consists of.
 * Capped per STATS window; the counters there still count every reject.
 *
 * Pure; the service calls it from its main looper.
 */
class RejectLog {
  private var far = 0
  private var blurry = 0

  /** The row text for this rejection, or null if it isn't logged. */
  fun take(reason: String, still: Boolean, lat: Double, lng: Double, acc: Double, speedMps: Double?): String? {
    val key = FAR_KEYS[reason]
    when {
      key != null -> if (far < FAR_PER_WINDOW) far++ else return null
      reason == "accuracy" && !still && acc <= BLURRY_MAX_ACC_M -> if (blurry < BLURRY_PER_WINDOW) blurry++ else return null
      else -> return null
    }
    // Locale.US: a comma-decimal device locale would garble the coordinates.
    val speed = speedMps?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
    return String.format(Locale.US, "REJ|%s|l=%.5f|g=%.5f|a=%d|s=%s", key ?: "acc", lat, lng, acc.roundToInt(), speed)
  }

  /** Start a new STATS window. */
  fun reset() {
    far = 0
    blurry = 0
  }

  companion object {
    /** Far rejections (the STATS keys), the ones a straight line hides. */
    private val FAR_KEYS = mapOf("teleport" to "tp", "unconfirmed" to "un", "inconsistent" to "ic")

    const val FAR_PER_WINDOW = 20
    const val BLURRY_PER_WINDOW = 10

    /** Blurrier fixes say nothing about a route. */
    const val BLURRY_MAX_ACC_M = 100.0
  }
}
