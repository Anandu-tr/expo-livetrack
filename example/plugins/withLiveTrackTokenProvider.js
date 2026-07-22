const { withDangerousMod, withAppBuildGradle } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Wires the app-supplied TokenProvider for expo-livetrack (Option B).
 *
 * The plugin (expo-livetrack) is auth-agnostic and loads a TokenProvider by
 * class name via reflection. This config plugin supplies that implementation in
 * the HOST app, so all Firebase coupling lives here, not in the tracker:
 *   1. writes FirebaseTokenProvider.kt into the app package, and
 *   2. adds firebase-auth + play-services-tasks to the app module (RNFirebase
 *      exposes firebase-auth only as `implementation`, so the app module can't
 *      otherwise compile against it).
 *
 * Works in a JS-less / killed-app process because the Firebase Auth SDK
 * auto-initializes and restores the persisted session before the worker runs.
 */

// Keep aligned with @react-native-firebase/app sdkVersions.android.firebase.
const FIREBASE_BOM = '33.12.0';
const APP_PACKAGE = 'com.example.expolivetrack';

const PROVIDER_KT = `package ${APP_PACKAGE}

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import expo.modules.livetrack.sync.TokenProvider

/**
 * App-supplied TokenProvider for expo-livetrack. Loaded by the plugin via
 * reflection (class name passed as StartConfig.tokenProviderClass), keeping the
 * plugin free of any Firebase dependency. Blocking by contract — the uploader
 * calls it on a background thread.
 */
class FirebaseTokenProvider : TokenProvider {
  override fun freshToken(forceRefresh: Boolean): String? {
    val user = FirebaseAuth.getInstance().currentUser ?: return null
    return runCatching { Tasks.await(user.getIdToken(forceRefresh)).token }.getOrNull()
  }
}
`;

function withProviderSource(config) {
  return withDangerousMod(config, [
    'android',
    (cfg) => {
      const dir = path.join(
        cfg.modRequest.platformProjectRoot,
        'app/src/main/java',
        ...APP_PACKAGE.split('.'),
      );
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, 'FirebaseTokenProvider.kt'), PROVIDER_KT);
      return cfg;
    },
  ]);
}

function withProviderDeps(config) {
  return withAppBuildGradle(config, (cfg) => {
    if (cfg.modResults.language !== 'groovy') {
      throw new Error('withLiveTrackTokenProvider: expected app/build.gradle to be groovy');
    }
    const marker = 'expo-livetrack token provider deps';
    if (cfg.modResults.contents.includes(marker)) return cfg;
    cfg.modResults.contents += `

// ${marker}: FirebaseTokenProvider compiles against firebase-auth here because
// @react-native-firebase exposes it only as 'implementation' (not visible to app).
dependencies {
    implementation platform("com.google.firebase:firebase-bom:${FIREBASE_BOM}")
    implementation "com.google.firebase:firebase-auth"
    implementation "com.google.android.gms:play-services-tasks"
}
`;
    return cfg;
  });
}

module.exports = function withLiveTrackTokenProvider(config) {
  return withProviderDeps(withProviderSource(config));
};
