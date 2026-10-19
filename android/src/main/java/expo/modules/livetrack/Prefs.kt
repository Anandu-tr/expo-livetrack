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

  /**
   * Times a row may be sent and come back unacknowledged under a 2xx before the
   * uploader evicts it. Bounds the re-upload loop; see `PointEntity.attempts`.
   */
  const val KEY_MAX_UPLOAD_ATTEMPTS = "maxUploadAttempts"

  /** Days of acknowledged location rows kept on-device for replay. 0 = archive off. */
  const val KEY_ARCHIVE_DAYS = "archiveDays"

  // Token provider: fully-qualified class name of a native TokenProvider impl the
  // uploader loads (reflection) to mint fresh tokens without the app's JS running.
  const val KEY_TOKEN_PROVIDER_CLASS = "tokenProviderClass"

  // Diagnostics config.
  const val KEY_DIAGNOSTICS_CRASHLYTICS = "diagnosticsCrashlytics"

  /**
   * Opt-in SERVICE_STARTED / SERVICE_STOPPED event rows. Off by default because
   * the ingest backend must accept the new event types first — an unknown type
   * that makes the server reject a batch would stall every point behind it.
   */
  const val KEY_DIAGNOSTICS_LIFECYCLE = "diagnosticsLifecycle"

  /** Set true by start(), false by stop(); read by BootReceiver/Watchdog. */
  const val KEY_WAS_TRACKING = "wasTracking"

  /** Debounce state for ProviderReceiver: last seen GPS-enabled boolean. */
  const val KEY_LAST_LOCATION_ENABLED = "lastLocationEnabled"

  /** Last seen "granted" permission state, for PERMISSION_REVOKED/GRANTED deltas. */
  const val KEY_LAST_PERMISSION_GRANTED = "lastPermissionGranted"

  /** Last seen battery-optimization-ignored state, for BATTERY_OPT_ON deltas. */
  const val KEY_LAST_BATTERY_OPT_IGNORED = "lastBatteryOptIgnored"

  /**
   * ensureNotKilled() throttle state: last time the raw OS "ignore battery
   * optimization" dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) was
   * launched, and how many consecutive attempts have gone by without the OS
   * confirming the exemption. See LiveTrackModule.ensureNotKilled.
   */
  const val KEY_ENSURE_NOT_KILLED_LAST_ATTEMPT_AT = "ensureNotKilledLastAttemptAt"
  const val KEY_ENSURE_NOT_KILLED_ATTEMPT_COUNT = "ensureNotKilledAttemptCount"

  /** Recent kill-recovery start times, see [expo.modules.livetrack.keepalive.KillStats]. */
  const val KEY_KILL_RESTARTS = "killRestarts"
}
