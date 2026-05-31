import type { EventSubscription } from 'expo-modules-core';

import LiveTrackModule from './LiveTrackModule';
import type {
  Cadence,
  LocationRecord,
  StartConfig,
  SyncError,
  TrackerEvent,
  TrackerState,
} from './LiveTrack.types';

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
