import { NativeModule, requireNativeModule } from 'expo';

import type { LiveTrackEvents, StartConfig, TrackerState } from './LiveTrack.types';

declare class LiveTrackModule extends NativeModule<LiveTrackEvents> {
  start(config: StartConfig): Promise<void>;
  stop(): Promise<void>;
  getState(): Promise<TrackerState>;
  requestPermissions(): Promise<TrackerState>;
  ensureNotKilled(): Promise<void>;
  requestEnableLocation(): Promise<boolean>;
}

export default requireNativeModule<LiveTrackModule>('LiveTrack');
