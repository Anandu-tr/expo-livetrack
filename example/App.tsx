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
  Pressable,
  Modal,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
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
}: {
  title: string;
  onPress: () => void;
  color?: string;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [
        {
          backgroundColor: color ?? '#2196F3',
          opacity: pressed ? 0.7 : 1,
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

  // Phone / OTP sign-in state. Both the number and the (test) OTP are entered
  // up front and submitted together.
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);

  // Controls our OWN prominent-disclosure modal (shown before start()).
  const [disclosureVisible, setDisclosureVisible] = useState(false);

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
    // Only prompt when permissions aren't already fully granted. A returning
    // user with location (incl. background) intact goes straight to tracking —
    // no dialog, no separate "Request Permissions" tap. Android keeps the grant
    // across launches, so this is a no-op on every run after the first.
    const state = await LiveTracker.getState();
    if (state.permission !== 'granted') {
      await LiveTracker.requestPermissions();
    }
    const idToken = await current.getIdToken();
    await LiveTracker.start({
      url: TRACK_URL,
      token: idToken,
      userId: current.uid,
      cadence: TEST_CADENCE,
    });
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
  const onRequestPermissions = async () => {
    try {
      const next = await LiveTracker.requestPermissions();
      setState(next);
    } catch (err) {
      Alert.alert('Permission request failed', String(err));
    }
  };

  const onEnsureNotKilled = async () => {
    try {
      await LiveTracker.ensureNotKilled();
    } catch (err) {
      Alert.alert('ensureNotKilled failed', String(err));
    }
  };

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

  // Phone / OTP sign-in in a single step. For a Firebase *test* number the code
  // is pre-defined, so there's no real SMS to wait for: we kick off
  // signInWithPhoneNumber (required to obtain a confirmation handle) and confirm
  // with the entered test OTP back-to-back. `user` then updates via
  // onAuthStateChanged, which switches the UI to the app screen.
  const onPhoneSignIn = async () => {
    if (!phone.trim()) {
      Alert.alert('Enter a phone number', 'Use E.164 format, e.g. +1 650-555-3434.');
      return;
    }
    if (!code.trim()) {
      Alert.alert('Enter the OTP', 'Enter the test OTP for this number.');
      return;
    }
    setBusy(true);
    try {
      // Test/fictional numbers resolve against their canned OTP without a real
      // Play Integrity / reCAPTCHA + SMS round-trip. Dev-only so production
      // keeps real verification.
      if (__DEV__) {
        auth().settings.appVerificationDisabledForTesting = true;
      }
      const c = await auth().signInWithPhoneNumber(phone.trim());
      await c.confirm(code.trim());
      // Clear local state; the auth listener handles the screen switch.
      setPhone('');
      setCode('');
    } catch (err: any) {
      Alert.alert('Phone sign-in failed', String(err?.message ?? err));
    } finally {
      setBusy(false);
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
    setPhone('');
    setCode('');
  };

  // Start is a TWO-step flow: show our prominent-disclosure modal FIRST, then
  // only call start() once the user explicitly accepts.
  const onStartPressed = () => {
    if (!user) {
      Alert.alert('Sign in first', 'Sign in with Google before starting tracking.');
      return;
    }
    setDisclosureVisible(true);
  };

  const onDisclosureAccept = async () => {
    setDisclosureVisible(false);
    if (!user) {
      return;
    }
    try {
      await startTracking(user);
    } catch (err) {
      Alert.alert('start failed', String(err));
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

  // Signed out → the ONLY thing the user can do is sign in (Google or phone/OTP).
  if (!user) {
    return (
      <SafeAreaView style={[styles.container, styles.center]}>
        <View style={styles.signInCard}>
          <Text style={styles.header}>expo-livetrack demo</Text>
          <Text style={styles.modalBody}>Sign in to start tracking.</Text>

          <Button title="Sign in with Google" onPress={onGoogleSignIn} />

          <View style={styles.divider} />

          <Text style={styles.hint}>Or sign in with a test phone number + OTP:</Text>
          <TextInput
            style={styles.input}
            value={phone}
            onChangeText={setPhone}
            placeholder="+1 650-555-3434"
            keyboardType="phone-pad"
            autoComplete="tel"
            editable={!busy}
          />
          <TextInput
            style={styles.input}
            value={code}
            onChangeText={setCode}
            placeholder="OTP (e.g. 123456)"
            keyboardType="number-pad"
            autoComplete="sms-otp"
            editable={!busy}
          />
          <Button
            title={busy ? 'Signing in…' : 'Sign in with phone'}
            onPress={onPhoneSignIn}
          />
        </View>
      </SafeAreaView>
    );
  }

  // Signed in → the full app.
  return (
    <SafeAreaView style={styles.container}>
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
          <Text style={styles.groupHeader}>Account</Text>
          <Text style={styles.line}>signed in: {user.email ?? user.uid}</Text>
          <Text style={styles.line}>uid: {user.uid}</Text>
          <View style={styles.buttonRow}>
            <Button title="Sign out" color="#555" onPress={onSignOut} />
          </View>
        </View>

        <View style={styles.group}>
          <Text style={styles.groupHeader}>Controls</Text>
          <View style={styles.buttonRow}>
            <Button title="Request Permissions" onPress={onRequestPermissions} />
          </View>
          <View style={styles.buttonRow}>
            <Button title="Ensure Not Killed" onPress={onEnsureNotKilled} />
          </View>
          <View style={styles.buttonRow}>
            <Button title="Start" onPress={onStartPressed} />
          </View>
          <View style={styles.buttonRow}>
            <Button title="Stop" color="#b00020" onPress={onStop} />
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
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#eee' },
  center: { justifyContent: 'center', alignItems: 'center', padding: 24 },
  signInCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 24,
    width: '100%',
    gap: 16,
  },
  divider: { height: 1, backgroundColor: '#ddd', marginVertical: 4 },
  hint: { fontSize: 14, color: '#555' },
  input: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 6,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 16,
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
  modalTitle: { fontSize: 20, fontWeight: '600', marginBottom: 12 },
  modalBody: { fontSize: 15, lineHeight: 21, marginBottom: 12 },
  modalButtons: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 8,
  },
});
