import {
  AndroidConfig,
  ConfigPlugin,
  withAndroidManifest,
  withInfoPlist,
} from '@expo/config-plugins';

/**
 * Plugin props for expo-livetrack.
 *
 * Store-compliance rule: both purpose strings are REQUIRED. Vague or missing
 * iOS location-usage descriptions get App Store submissions rejected, so the
 * plugin throws at prebuild rather than shipping a placeholder default.
 *
 *  - `locationWhenInUse` -> NSLocationWhenInUseUsageDescription
 *  - `locationAlways`    -> NSLocationAlwaysAndWhenInUseUsageDescription
 *                          AND NSLocationAlwaysUsageDescription (iOS < 11 fallback key)
 */
export interface LiveTrackPluginProps {
  locationWhenInUse: string;
  locationAlways: string;
}

const ANDROID_PERMISSIONS = [
  'android.permission.ACCESS_FINE_LOCATION',
  'android.permission.ACCESS_COARSE_LOCATION',
  'android.permission.ACCESS_BACKGROUND_LOCATION',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_LOCATION',
  'android.permission.RECEIVE_BOOT_COMPLETED',
  'android.permission.ACTIVITY_RECOGNITION',
  'android.permission.POST_NOTIFICATIONS',
];

const TRACKING_SERVICE = 'expo.modules.livetrack.TrackingService';
const BOOT_RECEIVER = 'expo.modules.livetrack.BootReceiver';
const PROVIDER_RECEIVER = 'expo.modules.livetrack.ProviderReceiver';

type ManifestApplication = AndroidConfig.Manifest.ManifestApplication & {
  service?: any[];
  receiver?: any[];
};

function action(name: string) {
  return { $: { 'android:name': name } };
}

function intentFilter(actions: string[]) {
  return [
    {
      action: actions.map(action),
    },
  ];
}

function upsertByName(list: any[], name: string, entry: any): any[] {
  const next = list.filter((item) => item?.$?.['android:name'] !== name);
  next.push(entry);
  return next;
}

const withLiveTrackAndroid: ConfigPlugin = (config) =>
  withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults;

    // Permissions
    for (const permission of ANDROID_PERMISSIONS) {
      AndroidConfig.Permissions.addPermission(manifest, permission);
    }

    const application = (manifest.manifest.application?.[0] ?? {}) as ManifestApplication;
    if (!manifest.manifest.application) {
      manifest.manifest.application = [application as any];
    }

    // Foreground tracking service
    const service = {
      $: {
        'android:name': TRACKING_SERVICE,
        'android:exported': 'false',
        'android:foregroundServiceType': 'location',
      } as any,
    };
    application.service = upsertByName(application.service ?? [], TRACKING_SERVICE, service);

    // Boot / package-replaced receiver (re-arms tracking after reboot or app update)
    const bootReceiver = {
      $: {
        'android:name': BOOT_RECEIVER,
        'android:exported': 'true',
        'android:enabled': 'true',
      } as any,
      'intent-filter': intentFilter([
        'android.intent.action.BOOT_COMPLETED',
        'android.intent.action.MY_PACKAGE_REPLACED',
      ]),
    };

    // Location-provider receiver (reacts to GPS being toggled).
    // Must be exported: PROVIDERS_CHANGED is a system broadcast sent from a
    // different UID, and a non-exported manifest receiver only receives
    // broadcasts from its own UID, so it would never fire otherwise.
    const providerReceiver = {
      $: {
        'android:name': PROVIDER_RECEIVER,
        'android:exported': 'true',
        'android:enabled': 'true',
      } as any,
      'intent-filter': intentFilter(['android.location.PROVIDERS_CHANGED']),
    };

    let receivers = application.receiver ?? [];
    receivers = upsertByName(receivers, BOOT_RECEIVER, bootReceiver);
    receivers = upsertByName(receivers, PROVIDER_RECEIVER, providerReceiver);
    application.receiver = receivers;

    return cfg;
  });

const withLiveTrackIos: ConfigPlugin<LiveTrackPluginProps> = (config, props) =>
  withInfoPlist(config, (cfg) => {
    const plist = cfg.modResults as Record<string, any>;

    const backgroundModes: string[] = Array.isArray(plist.UIBackgroundModes)
      ? plist.UIBackgroundModes
      : [];
    if (!backgroundModes.includes('location')) {
      backgroundModes.push('location');
    }
    plist.UIBackgroundModes = backgroundModes;

    plist.NSLocationWhenInUseUsageDescription = props.locationWhenInUse;
    plist.NSLocationAlwaysAndWhenInUseUsageDescription = props.locationAlways;
    plist.NSLocationAlwaysUsageDescription = props.locationAlways;

    return cfg;
  });

const withLiveTrack: ConfigPlugin<LiveTrackPluginProps> = (config, props) => {
  // Validate synchronously, at registration time, so a misconfigured host app
  // fails fast at prebuild instead of producing a store-rejectable binary.
  const missing: string[] = [];
  if (!props || typeof props.locationWhenInUse !== 'string' || !props.locationWhenInUse.trim()) {
    missing.push('locationWhenInUse');
  }
  if (!props || typeof props.locationAlways !== 'string' || !props.locationAlways.trim()) {
    missing.push('locationAlways');
  }
  if (missing.length > 0) {
    throw new Error(
      `expo-livetrack: missing required iOS location purpose string(s): ${missing.join(
        ', '
      )}. ` +
        `You must provide non-empty values via the plugin props in app.json/app.config — ` +
        `e.g. ["expo-livetrack", { "locationWhenInUse": "...", "locationAlways": "..." }]. ` +
        `Vague or absent purpose strings cause App Store rejection.`
    );
  }

  config = withLiveTrackAndroid(config);
  config = withLiveTrackIos(config, props);
  return config;
};

export default withLiveTrack;
