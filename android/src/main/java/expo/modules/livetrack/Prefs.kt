package expo.modules.livetrack

/**
 * Single source of truth for the SharedPreferences file name and keys used
 * across the module, the uploader, the boot receiver and the watchdog.
 *
 * The store is `livetrack_prefs` (the same file the module already writes in
 * `start`). Keep these constants in sync with [LiveTrackModule].
 */
object Prefs {
  const val NAME = "livetrack_prefs"

  // Config (written by LiveTrackModule.start).
  const val KEY_URL = "url"
  const val KEY_TOKEN = "token"
  const val KEY_USER_ID = "userId"
  const val KEY_MOVING_INTERVAL_MS = "movingIntervalMs"
  const val KEY_MOVING_DISTANCE_M = "movingDistanceM"
  const val KEY_STILL_INTERVAL_MS = "stillIntervalMs"
  const val KEY_MAX_ACCURACY_M = "maxAccuracyM"
  const val KEY_BATCH_SIZE = "batchSize"

  /** Set true by start(), false by stop(); read by BootReceiver/Watchdog. */
  const val KEY_WAS_TRACKING = "wasTracking"

  /** Debounce state for ProviderReceiver: last seen GPS-enabled boolean. */
  const val KEY_LAST_LOCATION_ENABLED = "lastLocationEnabled"

  /** Last seen "granted" permission state, for PERMISSION_REVOKED/GRANTED deltas. */
  const val KEY_LAST_PERMISSION_GRANTED = "lastPermissionGranted"

  /** Last seen battery-optimization-ignored state, for BATTERY_OPT_ON deltas. */
  const val KEY_LAST_BATTERY_OPT_IGNORED = "lastBatteryOptIgnored"
}
