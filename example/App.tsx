import {
  LiveTracker,
  type LocationRecord,
  type SyncError,
  type TrackerEvent,
  type TrackerState,
} from 'expo-livetrack';
import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Pressable,
  Modal,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

/**
 * The ingest endpoint the package uploads buffered points/events to.
 *
 * IMPORTANT: replace <YOUR-LAN-IP> with your dev machine's LAN IP address
 * (e.g. 192.168.1.42). A physical device / emulator CANNOT reach `localhost`
 * or `127.0.0.1` — that resolves to the device itself, not your machine.
 *
 * Start the in-package dummy server first (from the repo root):
 *   npm --prefix test-server start
 * It listens on port 8787 and accepts any non-empty Bearer token.
 */
const TRACK_URL = 'http://10.0.2.2:8787/locations/batch';

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

  // Controls our OWN prominent-disclosure modal (shown before start()).
  const [disclosureVisible, setDisclosureVisible] = useState(false);

  // Guard so we don't stack multiple "location is off" alerts.
  const locationOffAlertOpen = useRef(false);

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

    const syncErrorSub = LiveTracker.onSyncError((err) => {
      setSyncError(err);
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

  // Start is a TWO-step flow: show our prominent-disclosure modal FIRST, then
  // only call start() once the user explicitly accepts.
  const onStartPressed = () => {
    setDisclosureVisible(true);
  };

  const onDisclosureAccept = async () => {
    setDisclosureVisible(false);
    try {
      await LiveTracker.start({
        url: TRACK_URL,
        token: 'dev-token', // any non-empty bearer passes the dummy server
        userId: 'demo-user',
      });
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
