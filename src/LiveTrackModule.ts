import { NativeModule, requireNativeModule } from 'expo';

declare class LiveTrackModule extends NativeModule<{}> {}

export default requireNativeModule<LiveTrackModule>('LiveTrack');
