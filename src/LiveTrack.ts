import type { EventSubscription } from 'expo-modules-core';

import LiveTrackModule from './LiveTrackModule';
import type {
  ArchiveStats,
  Cadence,
  DayCoverage,
  ExportResult,
  LocationRecord,
  OemAutostartInfo,
  OemAutostartResult,
  ReplayResult,
  StartConfig,
  SyncError,
  TrackerEvent,
  TrackerState,
} from './LiveTrack.types';

/** Silence longer than this counts as a coverage gap (10 minutes). */
const DEFAULT_GAP_MS = 600_000;

/**
 * Default capture cadence. Callers override individual fields via
 * `StartConfig.cadence`; any field they omit falls back to these values.
 */
const DEFAULT_CADENCE: Required<Cadence> = {
  movingIntervalMs: 12000,
  movingDistanceM: 30,
  stillIntervalMs: 120000,
  batchSize: 50,
  maxAccuracyM: 50,
  maxUploadAttempts: 15,
};

/**
 * Thin JS control + event surface over the native LiveTrack module.
 *
 * This layer holds NO capture/buffer/retry logic — all of that lives in the
 * native module. Its only responsibilities are merging cadence defaults and
 * forwarding calls / wiring event subscriptions.
 */
export const LiveTracker = {
  /**
   * Start background tracking. Merges {@link DEFAULT_CADENCE} with any
   * caller-provided cadence (caller values win) and forwards to native.
   */
  start(config: StartConfig): Promise<void> {
    const cadence: Required<Cadence> = { ...DEFAULT_CADENCE, ...config.cadence };
    return LiveTrackModule.start({ ...config, cadence });
  },

  /** Stop tracking. Pass-through to native. */
  stop(): Promise<void> {
    return LiveTrackModule.stop();
  },

  /** Get the current tracker state. Pass-through to native. */
  getState(): Promise<TrackerState> {
    return LiveTrackModule.getState();
  },

  /** Request location permissions. Pass-through to native. */
  requestPermissions(): Promise<TrackerState> {
    return LiveTrackModule.requestPermissions();
  },

  /** Ensure the background service has not been killed. Pass-through to native. */
  ensureNotKilled(): Promise<void> {
    return LiveTrackModule.ensureNotKilled();
  },

  /**
   * Whether this device's manufacturer is a known aggressive background
   * killer (MIUI/ColorOS/FuntouchOS/realme UI) with its own separate
   * autostart allowlist outside the standard battery-optimization exemption.
   * Side-effect-free — use to decide whether to show an autostart prompt.
   */
  getOemAutostartInfo(): Promise<OemAutostartInfo> {
    return LiveTrackModule.getOemAutostartInfo();
  },

  /**
   * Best-effort launch of this OEM's autostart settings screen. Falls back
   * to the generic app-info screen if no known screen could be opened.
   */
  openAutostartSettings(): Promise<OemAutostartResult> {
    return LiveTrackModule.openAutostartSettings();
  },

  /**
   * Ask the OS to enable location services. Thin pass-through to native.
   *
   * NOTE: This package never shows a popup on its own. The host app decides
   * when to call this — e.g. from its own `LOCATION_OFF` event handler — so it
   * can control the UX. Resolves `true` if location services end up enabled.
   */
  requestEnableLocation(): Promise<boolean> {
    return LiveTrackModule.requestEnableLocation();
  },

  /**
   * Re-send archived location rows captured in `[fromMs, toMs)` to the ingest
   * URL with their ORIGINAL ids and times. Requires `archiveDays > 0` at start.
   * Rejects with code `ERR_REPLAY_AUTH` (refresh the token via `start()` and
   * retry) or `ERR_REPLAY_BUSY` (a replay is already running).
   */
  replay(fromMs: number, toMs: number): Promise<ReplayResult> {
    return LiveTrackModule.replay(fromMs, toMs);
  },

  /**
   * Summary of what this device captured in `[fromMs, toMs)`: archive plus
   * still-unsynced rows. `gapMs` always reaches native (no optional-arg
   * bridging differences between platforms).
   */
  getCoverage(fromMs: number, toMs: number, gapMs: number = DEFAULT_GAP_MS): Promise<DayCoverage> {
    return LiveTrackModule.getCoverage(fromMs, toMs, gapMs);
  },

  /** Delete the on-device archive. Call on sign-out. */
  clearArchive(): Promise<void> {
    return LiveTrackModule.clearArchive();
  },

  getArchiveStats(): Promise<ArchiveStats> {
    return LiveTrackModule.getArchiveStats();
  },

  /**
   * Archived rows for the current user with id > sinceId (oldest first, capped
   * at `limit`). Reads the stable on-device archive, not the live upload buffer —
   * unaffected by the native uploader's own upload-then-delete cycle. Requires
   * `archiveDays > 0` at `start()`.
   */
  exportArchive(sinceId: number, limit: number): Promise<ExportResult> {
    return LiveTrackModule.exportArchive(sinceId, limit);
  },

  /**
   * Subscribe to per-record location updates. Returns the subscription so the
   * caller can `.remove()` it.
   */
  onLocation(cb: (record: LocationRecord) => void): EventSubscription {
    return LiveTrackModule.addListener('onLocation', cb);
  },

  /**
   * Subscribe to lifecycle/diagnostic tracker events. Returns the subscription
   * so the caller can `.remove()` it.
   */
  onEvent(cb: (event: TrackerEvent) => void): EventSubscription {
    return LiveTrackModule.addListener('onEvent', cb);
  },

  /**
   * Subscribe to server sync errors. Returns the subscription so the caller
   * can `.remove()` it.
   */
  onSyncError(cb: (error: SyncError) => void): EventSubscription {
    return LiveTrackModule.addListener('onSyncError', cb);
  },
};
