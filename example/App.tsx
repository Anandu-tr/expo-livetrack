import auth, { type FirebaseAuthTypes } from '@react-native-firebase/auth';
import {
  GoogleSignin,
  statusCodes,
} from '@react-native-google-signin/google-signin';
import {
  LiveTracker,
  type LocationRecord,
  type StartConfig,
  type SyncError,
  type TrackerEvent,
  type TrackerState,
} from 'expo-livetrack';
import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  Platform,
  Pressable,
  Modal,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';

/**
 * The deployed `ingest` Cloud Function URL. After `firebase deploy`, the CLI
 * prints this (Gen-2 functions look like
 * `https://ingest-<hash>-<region>.a.run.app` or
 * `https://<region>-<project>.cloudfunctions.net/ingest`). Paste it here.
 */
const TRACK_URL = 'https://ingest-hxi7bsrfha-uc.a.run.app';

/**
 * The Firebase "Web client" OAuth 2.0 client ID (Auth → Sign-in method →
 * Google, or Google Cloud → Credentials → "Web client (auto created…)").
 * Required by @react-native-google-signin even on native, so Firebase can
 * exchange the Google ID token for a Firebase credential.
 */
const WEB_CLIENT_ID = '280378540912-8ag6ohgc7tgkpvu8mfnsb305kgjt7215.apps.googleusercontent.com';

// Aggressive cadence for indoor / sit-still testing. With the default 30m
// distance gate, FusedLocationProvider refuses to deliver callbacks until
// you've moved 30m, which never happens at a desk. distance=0 lets
// interval-only fixes flow.
const TEST_CADENCE: StartConfig['cadence'] = {
  movingIntervalMs: 5000,
  movingDistanceM: 0,
  stillIntervalMs: 15000,
  batchSize: 50,
  maxAccuracyM: 100,
};

// Configure Google Sign-In once at module load.
GoogleSignin.configure({ webClientId: WEB_CLIENT_ID });

// Local Button (Pressable-based). RN's built-in <Button> doesn't dispatch
// onPress reliably on the New Architecture (Fabric) in some RN 0.81 builds;
// Pressable is the official replacement.
function Button({
  title,
  onPress,
  color,
  disabled,
}: {
  title: string;
  onPress: () => void;
  color?: string;
  disabled?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        {
          backgroundColor: disabled ? '#b0b0b0' : color ?? '#2196F3',
          opacity: disabled ? 1 : pressed ? 0.7 : 1,
          paddingVertical: 10,
          paddingHorizontal: 16,
          borderRadius: 4,
          alignItems: 'center',
        },
      ]}>
      <Text style={{ color: 'white', fontWeight: '600', textTransform: 'uppercase' }}>{title}</Text>
    </Pressable>
  );
}

export default function App() {
  const [state, setState] = useState<TrackerState | null>(null);
  const [lastFix, setLastFix] = useState<LocationRecord | null>(null);
  const [events, setEvents] = useState<TrackerEvent[]>([]);
  const [syncError, setSyncError] = useState<SyncError | null>(null);

  // The signed-in Firebase user (null when signed out).
  const [user, setUser] = useState<FirebaseAuthTypes.User | null>(null);
  // True until Firebase reports the first auth state, so we don't flash the
  // sign-in screen while a persisted session is being restored.
  const [initializing, setInitializing] = useState(true);

  // Controls our OWN prominent-disclosure modal (shown before start()).
  const [disclosureVisible, setDisclosureVisible] = useState(false);
  // "Allow all the time" coaching modal, shown right before the OS permission
  // prompts so the user knows which option to pick on the background-location
  // settings screen.
  const [permHelpVisible, setPermHelpVisible] = useState(false);

  // Guard so we don't stack multiple "location is off" alerts.
  const locationOffAlertOpen = useRef(false);

  // Latest user kept in a ref so the onSyncError handler (registered once) can
  // always reach the current uid when refreshing an expired token.
  const userRef = useRef<FirebaseAuthTypes.User | null>(null);
  userRef.current = user;

  // ---------------------------------------------------------------------------
  // Start the tracker for a signed-in user: mint a fresh Firebase ID token and
  // pass it (+ the uid) to the module. The token becomes the upload Bearer; the
  // `ingest` function verifies it and writes to RTDB under tracks/<uid>.
  // ---------------------------------------------------------------------------
  const startTracking = async (current: FirebaseAuthTypes.User) => {
    const idToken = await current.getIdToken();
    await LiveTracker.start({
      url: TRACK_URL,
      token: idToken,
      userId: current.uid,
      cadence: TEST_CADENCE,
    });
  };

  // Poll getState() until location services (GPS) are ON, or time out.
  // requestEnableLocation() pops the OS dialog but resolves with the pre-decision
  // state, so the truth has to be re-read here after the user responds.
  const waitForGps = async (timeoutMs: number): Promise<boolean> => {
    const startedAt = Date.now();
    while (Date.now() - startedAt < timeoutMs) {
      const s = await LiveTracker.getState();
      if (s.locationServices) return true;
      await new Promise((r) => setTimeout(r, 500));
    }
    return false;
  };

  // ---------------------------------------------------------------------------
  // Auth: keep `user` in sync with Firebase, and restore the auth UI on launch.
  // ---------------------------------------------------------------------------
  useEffect(
    () =>
      auth().onAuthStateChanged((next) => {
        setUser(next);
        setInitializing(false);
      }),
    []
  );

  // ---------------------------------------------------------------------------
  // Subscriptions: wire up the three package event streams, poll getState(),
  // and tear everything down on unmount.
  // ---------------------------------------------------------------------------
  useEffect(() => {
    const locationSub = LiveTracker.onLocation((record) => {
      setLastFix(record);
    });

    const eventSub = LiveTracker.onEvent((evt) => {
      // Keep the most recent 50 events for the on-screen log.
      setEvents((prev) => [evt, ...prev].slice(0, 50));

      // ---------------------------------------------------------------------
      // APP-CONTROLLED ENABLE-LOCATION POPUP — the whole point of this demo.
      //
      // This popup (its copy, styling and CTA) is defined by THE APP, not the
      // package. The package only emits a `LOCATION_OFF` event and exposes
      // `LiveTracker.requestEnableLocation()`. The app decides whether/when/how
      // to prompt the user and which words to use.
      // ---------------------------------------------------------------------
      if (evt.e === 'LOCATION_OFF' && !locationOffAlertOpen.current) {
        locationOffAlertOpen.current = true;
        Alert.alert(
          'Turn on location',
          'Location services are off, so we can’t track your route while you’re ' +
            'on the job. Turn location back on to keep your visits and distance ' +
            'recorded.',
          [
            {
              text: 'Not now',
              style: 'cancel',
              onPress: () => {
                locationOffAlertOpen.current = false;
              },
            },
            {
              text: 'Turn on location',
              onPress: async () => {
                locationOffAlertOpen.current = false;
                // The app's CTA triggers the package's pass-through call.
                await LiveTracker.requestEnableLocation();
              },
            },
          ],
          { cancelable: true, onDismiss: () => (locationOffAlertOpen.current = false) }
        );
      }
    });

    const syncErrorSub = LiveTracker.onSyncError(async (err) => {
      setSyncError(err);

      // Firebase ID tokens expire after ~1h. The module captured the token at
      // start(), so once it expires the function returns 401/403. Refresh the
      // token and restart the tracker with the new one.
      if ((err.status === 401 || err.status === 403) && userRef.current) {
        try {
          await startTracking(userRef.current);
          setSyncError(null);
        } catch {
          // Leave the banner up; user can retry from the UI.
        }
      }
    });

    // Poll the tracker state every 3s for the live status panel.
    let cancelled = false;
    const refreshState = async () => {
      try {
        const next = await LiveTracker.getState();
        if (!cancelled) {
          setState(next);
        }
      } catch {
        // getState can reject before the native module is ready; ignore.
      }
    };
    refreshState();
    const interval = setInterval(refreshState, 3000);

    return () => {
      cancelled = true;
      clearInterval(interval);
      locationSub.remove();
      eventSub.remove();
      syncErrorSub.remove();
    };
  }, []);

  // ---------------------------------------------------------------------------
  // Button handlers
  // ---------------------------------------------------------------------------

  // Sign in with Google → exchange for a Firebase credential.
  const onGoogleSignIn = async () => {
    try {
      await GoogleSignin.hasPlayServices({ showPlayServicesUpdateDialog: true });
      const result = await GoogleSignin.signIn();
      // v13 returns a discriminated response; cancellation is not an error.
      if (result.type === 'cancelled') {
        return;
      }
      const idToken = result.data?.idToken;
      if (!idToken) {
        throw new Error('No Google ID token returned');
      }
      const credential = auth.GoogleAuthProvider.credential(idToken);
      await auth().signInWithCredential(credential);
      // `user` updates via the onAuthStateChanged subscription.
    } catch (err: any) {
      if (err?.code === statusCodes.SIGN_IN_CANCELLED) {
        return; // older SDKs throw on cancel — still not an error
      }
      Alert.alert('Google sign-in failed', String(err?.message ?? err));
    }
  };

  const onSignOut = async () => {
    try {
      await LiveTracker.stop();
    } catch {
      // ignore — may not be tracking
    }
    try {
      await GoogleSignin.signOut();
      await auth().signOut();
    } catch (err) {
      Alert.alert('Sign-out failed', String(err));
    }
  };

  // Start is the single entry point: show the prominent-disclosure modal FIRST
  // (store policy requires it before requesting background location), then on
  // accept run the whole setup chain — permission prompts, battery-optimisation
  // exemption — and finally start tracking.
  const onStartPressed = () => {
    if (!user) {
      Alert.alert('Sign in first', 'Sign in with Google before starting tracking.');
      return;
    }
    setDisclosureVisible(true);
  };

  // Disclosure accepted → show the "Allow all the time" coaching modal next,
  // BEFORE the OS prompts, so the user is primed for the background-location
  // settings screen.
  const onDisclosureAccept = () => {
    setDisclosureVisible(false);
    if (!user) {
      return;
    }
    setPermHelpVisible(true);
  };

  // Coaching modal dismissed → now run the real permission + start sequence.
  const onPermHelpContinue = async () => {
    setPermHelpVisible(false);
    if (!user) {
      return;
    }
    try {
      // 1) Location permissions (foreground, then background on Android 11+).
      const next = await LiveTracker.requestPermissions();
      setState(next);

      // 2) GPS / location services must be ON for tracking. If off, pop the OS
      //    one-tap "turn on location" dialog, which enables GPS in place (no trip
      //    to Settings), then give it a moment to take effect before continuing.
      if (!next.locationServices) {
        await LiveTracker.requestEnableLocation();
        await waitForGps(8000);
      }

      // 3) Ask the OS to exempt us from battery optimisation (keep-alive popup).
      await LiveTracker.ensureNotKilled();
      // 4) Start the foreground tracking service.
      await startTracking(user);
    } catch (err) {
      Alert.alert('Could not start tracking', String(err));
    }
  };

  const onStop = async () => {
    try {
      await LiveTracker.stop();
    } catch (err) {
      Alert.alert('stop failed', String(err));
    }
  };

  // While restoring a persisted session, show a spinner instead of flashing
  // the sign-in screen.
  if (initializing) {
    return (
      <SafeAreaView style={[styles.container, styles.center]}>
        <ActivityIndicator size="large" color="#2196F3" />
      </SafeAreaView>
    );
  }

  // Signed out → the ONLY thing the user can do is sign in with Google.
  if (!user) {
    return (
      <SafeAreaView style={[styles.container, styles.center]}>
        <View style={styles.signInCard}>
          <Text style={styles.header}>expo-livetrack demo</Text>
          <Text style={styles.modalBody}>Sign in to start tracking.</Text>
          <Button title="Sign in with Google" onPress={onGoogleSignIn} />
        </View>
      </SafeAreaView>
    );
  }

  // Signed in → the full app.
  return (
    <SafeAreaView style={[styles.container, styles.safeTop]}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.header}>expo-livetrack demo</Text>

        {syncError ? (
          <View style={styles.banner}>
            <Text style={styles.bannerText}>
              Sync error: {syncError.message}
              {syncError.status ? ` (HTTP ${syncError.status})` : ''}
              {typeof syncError.bufferedCount === 'number'
                ? ` — ${syncError.bufferedCount} buffered`
                : ''}
            </Text>
          </View>
        ) : null}

        <View style={styles.group}>
          <View style={styles.accountHeader}>
            <Text style={styles.groupHeader}>Account</Text>
            <Pressable onPress={onSignOut} hitSlop={8}>
              <Text style={styles.linkText}>Sign out</Text>
            </Pressable>
          </View>
          <Text style={styles.line}>signed in: {user.email ?? user.uid}</Text>
          <Text style={styles.line}>uid: {user.uid}</Text>
        </View>

        <View style={styles.group}>
          <Text style={styles.groupHeader}>Controls</Text>
          <View style={styles.buttonRow}>
            <Button
              title="Start"
              onPress={onStartPressed}
              disabled={state?.tracking === true}
            />
          </View>
          <View style={styles.buttonRow}>
            <Button
              title="Stop"
              color="#b00020"
              onPress={onStop}
              disabled={!state?.tracking}
            />
          </View>
        </View>

        <View style={styles.group}>
          <Text style={styles.groupHeader}>Status</Text>
          <Text style={styles.line}>
            tracking: {state ? String(state.tracking) : '—'}
          </Text>
          <Text style={styles.line}>
            permission: {state ? state.permission : '—'}
          </Text>
          <Text style={styles.line}>
            locationServices: {state ? String(state.locationServices) : '—'}
          </Text>
          <Text style={styles.line}>
            bufferedCount: {state ? String(state.bufferedCount) : '—'}
          </Text>
        </View>

        <View style={styles.group}>
          <Text style={styles.groupHeader}>Last fix</Text>
          {lastFix ? (
            <Text style={styles.line}>
              {lastFix.l.toFixed(5)}, {lastFix.g.toFixed(5)} · acc {lastFix.acc}m ·{' '}
              {new Date(lastFix.t).toLocaleTimeString()}
            </Text>
          ) : (
            <Text style={styles.line}>no fix yet</Text>
          )}
        </View>

        <View style={styles.group}>
          <Text style={styles.groupHeader}>Event log</Text>
          {events.length === 0 ? (
            <Text style={styles.line}>no events yet</Text>
          ) : (
            events.map((evt, i) => (
              <Text key={`${evt.t}-${i}`} style={styles.line}>
                {new Date(evt.t).toLocaleTimeString()} · {evt.e}
              </Text>
            ))
          )}
        </View>
      </ScrollView>

      {/* App-defined prominent-disclosure modal, shown BEFORE start(). Its copy
          is store-compliant: it names location collection, says it happens in
          the background even when the app is closed, and states the purpose. */}
      <Modal
        visible={disclosureVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setDisclosureVisible(false)}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>Background location</Text>
            <Text style={styles.modalBody}>
              This app collects your location data to track your field route and
              distance travelled — including in the background, even when the app
              is closed or not in use. Your location is sent securely to your
              organisation’s backend and is used only for route and distance
              reporting.
            </Text>
            <Text style={styles.modalBody}>
              You can stop tracking at any time from this screen. Tap “Allow &
              continue” to enable background location tracking.
            </Text>
            <View style={styles.modalButtons}>
              <Button title="No thanks" onPress={() => setDisclosureVisible(false)} />
              <Button title="Allow & continue" onPress={onDisclosureAccept} />
            </View>
          </View>
        </View>
      </Modal>

      {/* "Allow all the time" coaching modal — shown right before the OS prompts.
          Android requests background location on a SETTINGS screen where the user
          must pick "Allow all the time"; the screenshot makes that unmissable. */}
      <Modal
        visible={permHelpVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setPermHelpVisible(false)}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>Choose “Allow all the time”</Text>
            <Text style={styles.modalBody}>
              On the next screens, grant location access and — when asked about
              background location — select{' '}
              <Text style={{ fontWeight: '700' }}>“Allow all the time”</Text>. The app
              needs this to keep recording your route in the background.
            </Text>
            <Image
              source={require('./assets/allow-all-time.png')}
              style={styles.permHelpImage}
              resizeMode="contain"
            />
            <View style={styles.modalButtons}>
              <Button title="Not now" onPress={() => setPermHelpVisible(false)} />
              <Button title="Continue" onPress={onPermHelpContinue} />
            </View>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#eee' },
  // RN's SafeAreaView only insets on iOS; pad past the status bar on Android too.
  safeTop: { paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight ?? 0 : 0 },
  center: { justifyContent: 'center', alignItems: 'center', padding: 24 },
  signInCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 24,
    width: '100%',
    gap: 16,
  },
  content: { padding: 16 },
  header: { fontSize: 28, fontWeight: '600', marginBottom: 16 },
  group: {
    marginBottom: 16,
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 16,
  },
  groupHeader: { fontSize: 18, fontWeight: '600', marginBottom: 12 },
  accountHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  linkText: { color: '#2196F3', fontWeight: '600' },
  buttonRow: { marginBottom: 8 },
  line: { fontSize: 14, marginBottom: 4, fontFamily: 'Courier' },
  banner: {
    backgroundColor: '#fdecea',
    borderColor: '#b00020',
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    marginBottom: 16,
  },
  bannerText: { color: '#b00020' },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    padding: 24,
  },
  modalCard: { backgroundColor: '#fff', borderRadius: 12, padding: 20 },
  permHelpImage: {
    width: '100%',
    height: 320,
    borderRadius: 8,
    backgroundColor: '#eee',
    marginBottom: 12,
  },
  modalTitle: { fontSize: 20, fontWeight: '600', marginBottom: 12 },
  modalBody: { fontSize: 15, lineHeight: 21, marginBottom: 12 },
  modalButtons: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 8,
  },
});
