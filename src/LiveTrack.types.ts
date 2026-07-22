export type EventType =
  | 'LOCATION_OFF'
  | 'LOCATION_ON'
  | 'PERMISSION_REVOKED'
  | 'PERMISSION_GRANTED'
  | 'MOCK_DETECTED'
  | 'BATTERY_OPT_ON'
  | 'HEARTBEAT';

export interface Cadence {
  movingIntervalMs?: number; // default 12000
  movingDistanceM?: number; // default 30
  stillIntervalMs?: number; // default 120000
  batchSize?: number; // default 50
  maxAccuracyM?: number; // default 50
}

/** Opt-in operational diagnostics. `crashlytics` routes native upload/SQL failures to Crashlytics. */
export interface Diagnostics {
  crashlytics?: boolean;
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
}

export interface TrackerState {
  tracking: boolean;
  permission: 'granted' | 'denied' | 'background';
  locationServices: boolean;
  batteryOptIgnored: boolean;
  bufferedCount: number;
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

export interface TrackerEvent {
  e: EventType;
  t: number;
  l?: number;
  g?: number;
  b?: number;
}

export interface SyncError {
  message: string;
  status?: number;
  bufferedCount?: number;
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
