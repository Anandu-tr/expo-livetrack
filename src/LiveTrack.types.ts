export type EventType =
  | 'LOCATION_OFF'
  | 'LOCATION_ON'
  | 'PERMISSION_REVOKED'
  | 'PERMISSION_GRANTED'
  | 'MOCK_DETECTED'
  | 'BATTERY_OPT_ON'
  | 'HEARTBEAT'
  /** Opt-in via `Diagnostics.lifecycleEvents`; `r` carries the start reason. */
  | 'SERVICE_STARTED'
  /** Opt-in via `Diagnostics.lifecycleEvents`; `r` carries the stop reason. */
  | 'SERVICE_STOPPED';

export interface Cadence {
  movingIntervalMs?: number; // default 12000
  movingDistanceM?: number; // default 30
  stillIntervalMs?: number; // default 120000
  batchSize?: number; // default 50, clamped to 1..500 natively (SQL variable limit)
  maxAccuracyM?: number; // default 50
  /**
   * How many times a row may be sent and come back unacknowledged under an HTTP
   * 2xx before it is dropped from the buffer and reported. Default 15.
   *
   * Only "server answered 2xx and named none of your rows" counts — network
   * errors, 4xx and 5xx are retried losslessly and never consume the budget. This
   * bounds what would otherwise be an infinite re-upload loop against a server
   * whose ack format the client cannot read.
   */
  maxUploadAttempts?: number; // default 15
}

/** Opt-in operational diagnostics. `crashlytics` routes native upload/SQL failures to Crashlytics. */
export interface Diagnostics {
  crashlytics?: boolean;
  /**
   * Buffer SERVICE_STARTED / SERVICE_STOPPED event rows (with a reason such as
   * `boot`, `watchdog`, `sticky_restart`, `task_removed`) so kills and restarts
   * of the background service become visible server-side. Default false: the
   * ingest backend must accept these event types first — a batch it rejects
   * would stall every point behind it.
   */
  lifecycleEvents?: boolean;
}

export interface StartConfig {
  url: string;
  token: string;
  userId: string;
  cadence?: Cadence;
  /**
   * Fully-qualified class name of a native `TokenProvider` implementation that
   * mints a fresh auth token on demand (so the background uploader can refresh
   * even when the app's JS is not running). The plugin loads it by reflection,
   * staying auth-vendor-agnostic; the implementation lives in the host app. For
   * Firebase, point this at a class that calls
   * `FirebaseAuth.getInstance().currentUser.getIdToken(...)`.
   */
  tokenProviderClass?: string;
  diagnostics?: Diagnostics;
  /**
   * Days of server-acknowledged location rows kept on the device (separate
   * `archived_points` table) so a day can be audited with `getCoverage()` or
   * re-sent with `replay()` if the server lost it. 0 or omitted = off. Clamped
   * natively to 0..365 and to 400 000 rows in total. Cleared by `clearArchive()`
   * — call it on sign-out; the archive is location history.
   */
  archiveDays?: number;
}

/** Result of `replay()`: rows re-sent from the archive and how many the server acknowledged. */
export interface ReplayResult {
  sent: number;
  accepted: number;
  batches: number;
  failedBatches: number;
}

/** A silence longer than the gap threshold, with the straight-line distance covered meanwhile. */
export interface CoverageGap {
  fromT: number;
  toT: number;
  durationMs: number;
  distanceM: number;
}

/** Result of `getCoverage()`: archive ∪ still-unsynced buffer for the window. */
export interface DayCoverage {
  count: number;
  firstT: number | null;
  lastT: number | null;
  gaps: CoverageGap[];
}

export interface ArchiveStats {
  count: number;
  oldestT: number | null;
  /** Whole buffer database on disk (upper bound for the archive). */
  sizeBytes: number;
}

export interface TrackerState {
  tracking: boolean;
  permission: 'granted' | 'denied' | 'background';
  locationServices: boolean;
  batteryOptIgnored: boolean;
  bufferedCount: number;
  /**
   * Android only: times in the last 24 h the tracking service had to be
   * recovered after the OS/OEM killed it silently (watchdog, sticky restart or
   * Activity Recognition restart). A high value means the device's battery
   * manager (e.g. MIUI "Battery saver") is restricting the app.
   */
  killRestarts24h?: number;
}

export interface LocationRecord {
  l: number;
  g: number;
  t: number;
  s: number;
  acc: number;
  b: number;
  c: boolean;
  act: string;
  mock: boolean;
}

/** Result of `exportArchive()`: archived rows newer than the given cursor. */
export interface ExportResult {
  rows: LocationRecord[];
  /** New cursor to pass as `sinceId` next time (unchanged from the input if `rows` is empty). */
  lastId: number;
}

/** Result of `getOemAutostartInfo()`: whether this device needs an autostart prompt. */
export interface OemAutostartInfo {
  manufacturer: string;
  /** `Build.BRAND`, e.g. "POCO" on a Xiaomi-made POCO phone. */
  brand?: string;
  hasKnownSettings: boolean;
  /** Xiaomi / POCO / Redmi: `openBatterySaverSettings()` can reach the MIUI per-app battery saver. */
  hasBatterySaverSettings?: boolean;
}

/** Result of `openAutostartSettings()`: whether a known OEM screen was reached. */
export interface OemAutostartResult {
  attempted: boolean;
  manufacturer: string;
}

export interface TrackerEvent {
  e: EventType;
  t: number;
  l?: number;
  g?: number;
  b?: number;
  /**
   * Reason. SERVICE_STARTED / SERVICE_STOPPED: why — stops include `shutdown`
   * (phone switched off), `force_stop` and `killed_*` (from Android's record of
   * how the process ended, at its real time). HEARTBEAT (stored rows): `STILL`,
   * `STILL_NOAR` (still detected from speed because Activity Recognition was
   * silent), `NET_OFF` / `NET_ON` (internet lost / back) or a `STATS|...`
   * diagnostics summary.
   * LOCATION_ON/OFF, PERMISSION_* and BATTERY_OPT_ON: `reconcile` when the
   * change was found later (service start / periodic check) rather than as it
   * happened; `t` is then when it was noticed.
   */
  r?: string;
}

export interface SyncError {
  message: string;
  status?: number;
  bufferedCount?: number;
  /**
   * Present only when rows were evicted after exhausting `maxUploadAttempts`.
   * Non-zero means buffered data was dropped without a server ACK.
   */
  droppedCount?: number;
}

/**
 * Event map exposed by the native LiveTrack module's EventEmitter.
 * - onLocation: emitted for each captured/flushed location record.
 * - onEvent: lifecycle/diagnostic events (location toggled, permission, mock, etc.).
 * - onSyncError: emitted when uploading buffered records to the server fails.
 */
export type LiveTrackEvents = {
  onLocation: (record: LocationRecord) => void;
  onEvent: (event: TrackerEvent) => void;
  onSyncError: (error: SyncError) => void;
};
