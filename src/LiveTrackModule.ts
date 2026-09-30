import { NativeModule, requireNativeModule } from 'expo';

import type {
  ArchiveStats,
  DayCoverage,
  LiveTrackEvents,
  ReplayResult,
  StartConfig,
  TrackerState,
} from './LiveTrack.types';

declare class LiveTrackModule extends NativeModule<LiveTrackEvents> {
  start(config: StartConfig): Promise<void>;
  stop(): Promise<void>;
  getState(): Promise<TrackerState>;
  requestPermissions(): Promise<TrackerState>;
  ensureNotKilled(): Promise<void>;
  requestEnableLocation(): Promise<boolean>;
  replay(fromMs: number, toMs: number): Promise<ReplayResult>;
  getCoverage(fromMs: number, toMs: number, gapMs: number): Promise<DayCoverage>;
  clearArchive(): Promise<void>;
  getArchiveStats(): Promise<ArchiveStats>;
}

export default requireNativeModule<LiveTrackModule>('LiveTrack');
