import { AndroidConfig } from '@expo/config-plugins';
import type { ExpoConfig } from '@expo/config-types';

import withLiveTrack, { LiveTrackPluginProps } from '../index';

const VALID_PROPS: LiveTrackPluginProps = {
  locationWhenInUse: 'We use your location to show you on the live map while the app is open.',
  locationAlways:
    'We track your location in the background so teammates can see your live position during a trip.',
};

function baseConfig(): ExpoConfig {
  return { name: 'test-app', slug: 'test-app' };
}

function emptyManifest(): AndroidConfig.Manifest.AndroidManifest {
  return {
    manifest: {
      $: { 'xmlns:android': 'http://schemas.android.com/apk/res/android' },
      'uses-permission': [],
      application: [{ $: { 'android:name': '.MainApplication' } as any }],
    },
  };
}

async function applyAndroid(props: LiveTrackPluginProps) {
  const config = withLiveTrack(baseConfig(), props) as any;
  const result = await config.mods.android.manifest({
    ...config,
    modResults: emptyManifest(),
    modRequest: {} as any,
  });
  return result.modResults as AndroidConfig.Manifest.AndroidManifest;
}

async function applyIos(props: LiveTrackPluginProps) {
  const config = withLiveTrack(baseConfig(), props) as any;
  const result = await config.mods.ios.infoPlist({
    ...config,
    modResults: {},
    modRequest: {} as any,
  });
  return result.modResults as Record<string, any>;
}

describe('withLiveTrack store-compliance validation', () => {
  it('throws if iOS purpose strings are missing entirely', () => {
    expect(() => withLiveTrack(baseConfig(), {} as any)).toThrow(/purpose string/i);
  });

  it('throws if locationWhenInUse is missing', () => {
    expect(() =>
      withLiveTrack(baseConfig(), { locationAlways: 'x' } as any)
    ).toThrow(/purpose string/i);
  });

  it('throws if locationAlways is missing', () => {
    expect(() =>
      withLiveTrack(baseConfig(), { locationWhenInUse: 'x' } as any)
    ).toThrow(/purpose string/i);
  });

  it('throws if a purpose string is empty/whitespace', () => {
    expect(() =>
      withLiveTrack(baseConfig(), { locationWhenInUse: '   ', locationAlways: 'x' } as any)
    ).toThrow(/purpose string/i);
  });
});

describe('withLiveTrack Android manifest', () => {
  const EXPECTED_PERMISSIONS = [
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_BACKGROUND_LOCATION',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_LOCATION',
    'android.permission.RECEIVE_BOOT_COMPLETED',
    'android.permission.ACTIVITY_RECOGNITION',
    'android.permission.POST_NOTIFICATIONS',
  ];

  it('adds all required permissions', async () => {
    const manifest = await applyAndroid(VALID_PROPS);
    const names = (manifest.manifest['uses-permission'] ?? []).map(
      (p) => p.$['android:name']
    );
    for (const perm of EXPECTED_PERMISSIONS) {
      expect(names).toContain(perm);
    }
  });

  it('declares the TrackingService with correct attributes', async () => {
    const manifest = await applyAndroid(VALID_PROPS);
    const app = manifest.manifest.application![0] as any;
    const service = (app.service ?? []).find(
      (s: any) => s.$['android:name'] === 'expo.modules.livetrack.TrackingService'
    );
    expect(service).toBeDefined();
    expect(service.$['android:foregroundServiceType']).toBe('location');
    expect(service.$['android:exported']).toBe('false');
  });

  it('declares the BootReceiver with BOOT_COMPLETED and MY_PACKAGE_REPLACED filters', async () => {
    const manifest = await applyAndroid(VALID_PROPS);
    const app = manifest.manifest.application![0] as any;
    const receiver = (app.receiver ?? []).find(
      (r: any) => r.$['android:name'] === 'expo.modules.livetrack.BootReceiver'
    );
    expect(receiver).toBeDefined();
    expect(receiver.$['android:exported']).toBe('true');
    const actions = receiver['intent-filter']
      .flatMap((f: any) => f.action)
      .map((a: any) => a.$['android:name']);
    expect(actions).toContain('android.intent.action.BOOT_COMPLETED');
    expect(actions).toContain('android.intent.action.MY_PACKAGE_REPLACED');
  });

  it('declares the ProviderReceiver with PROVIDERS_CHANGED filter', async () => {
    const manifest = await applyAndroid(VALID_PROPS);
    const app = manifest.manifest.application![0] as any;
    const receiver = (app.receiver ?? []).find(
      (r: any) => r.$['android:name'] === 'expo.modules.livetrack.ProviderReceiver'
    );
    expect(receiver).toBeDefined();
    // System broadcast (sent from another UID) -> receiver must be exported.
    expect(receiver.$['android:exported']).toBe('true');
    const actions = receiver['intent-filter']
      .flatMap((f: any) => f.action)
      .map((a: any) => a.$['android:name']);
    expect(actions).toContain('android.location.PROVIDERS_CHANGED');
  });
});

describe('withLiveTrack iOS Info.plist', () => {
  it('adds location to UIBackgroundModes', async () => {
    const plist = await applyIos(VALID_PROPS);
    expect(plist.UIBackgroundModes).toContain('location');
  });

  it('sets the three NSLocation purpose strings from props', async () => {
    const plist = await applyIos(VALID_PROPS);
    expect(plist.NSLocationWhenInUseUsageDescription).toBe(VALID_PROPS.locationWhenInUse);
    expect(plist.NSLocationAlwaysAndWhenInUseUsageDescription).toBe(VALID_PROPS.locationAlways);
    expect(plist.NSLocationAlwaysUsageDescription).toBe(VALID_PROPS.locationAlways);
  });
});
