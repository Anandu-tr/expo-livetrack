import { registerWebModule, NativeModule } from 'expo';

// LiveTrackModule is not available on the web platform.
class LiveTrackModule extends NativeModule<{}> {}

export default registerWebModule(LiveTrackModule, 'LiveTrackModule');
