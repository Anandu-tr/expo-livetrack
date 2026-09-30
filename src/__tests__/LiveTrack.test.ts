jest.mock('../LiveTrackModule', () => ({
  __esModule: true,
  default: {
    start: jest.fn(),
    stop: jest.fn(),
    getState: jest.fn(),
    requestPermissions: jest.fn(),
    ensureNotKilled: jest.fn(),
    requestEnableLocation: jest.fn(),
    replay: jest.fn(),
    getCoverage: jest.fn(),
    clearArchive: jest.fn(),
    getArchiveStats: jest.fn(),
    addListener: jest.fn(() => ({ remove: jest.fn() })),
  },
}));

import native from '../LiveTrackModule';
import { LiveTracker } from '../LiveTrack';

beforeEach(() => {
  jest.clearAllMocks();
});

test('start fills cadence defaults and forwards to native', async () => {
  await LiveTracker.start({ url: 'u', token: 'tok', userId: 'x' });
  expect(native.start).toHaveBeenCalledWith(
    expect.objectContaining({
      url: 'u',
      token: 'tok',
      userId: 'x',
      cadence: expect.objectContaining({
        movingIntervalMs: 12000,
        movingDistanceM: 30,
        stillIntervalMs: 120000,
        batchSize: 50,
        maxAccuracyM: 50,
        maxUploadAttempts: 15,
      }),
    }),
  );
});

test('start forwards tokenProviderClass and diagnostics to native', async () => {
  await LiveTracker.start({
    url: 'u',
    token: 'tok',
    userId: 'x',
    tokenProviderClass: 'com.example.expolivetrack.FirebaseTokenProvider',
    diagnostics: { crashlytics: true },
  });
  expect(native.start).toHaveBeenCalledWith(
    expect.objectContaining({
      tokenProviderClass: 'com.example.expolivetrack.FirebaseTokenProvider',
      diagnostics: { crashlytics: true },
    }),
  );
});

test('caller cadence overrides win', async () => {
  await LiveTracker.start({ url: 'u', token: 't', userId: 'x', cadence: { movingIntervalMs: 5000 } });
  const arg = (native.start as jest.Mock).mock.calls.at(-1)![0];
  expect(arg.cadence.movingIntervalMs).toBe(5000);
  expect(arg.cadence.batchSize).toBe(50); // other defaults still applied
});

test('maxUploadAttempts defaults to 15 and is overridable', async () => {
  await LiveTracker.start({ url: 'u', token: 't', userId: 'x' });
  expect((native.start as jest.Mock).mock.calls.at(-1)![0].cadence.maxUploadAttempts).toBe(15);

  await LiveTracker.start({ url: 'u', token: 't', userId: 'x', cadence: { maxUploadAttempts: 2 } });
  expect((native.start as jest.Mock).mock.calls.at(-1)![0].cadence.maxUploadAttempts).toBe(2);
});

test('pass-through methods forward to native', async () => {
  await LiveTracker.stop();
  await LiveTracker.getState();
  await LiveTracker.requestPermissions();
  await LiveTracker.ensureNotKilled();
  await LiveTracker.requestEnableLocation();
  expect(native.stop).toHaveBeenCalled();
  expect(native.getState).toHaveBeenCalled();
  expect(native.requestPermissions).toHaveBeenCalled();
  expect(native.ensureNotKilled).toHaveBeenCalled();
  expect(native.requestEnableLocation).toHaveBeenCalled();
});

test('start forwards archiveDays untouched', async () => {
  await LiveTracker.start({ url: 'u', token: 't', userId: 'x', archiveDays: 45 });
  expect((native.start as jest.Mock).mock.calls.at(-1)![0].archiveDays).toBe(45);
});

test('archive methods forward to native; getCoverage defaults gapMs to 10 minutes', async () => {
  await LiveTracker.replay(1, 2);
  expect(native.replay).toHaveBeenCalledWith(1, 2);

  await LiveTracker.getCoverage(10, 20);
  expect(native.getCoverage).toHaveBeenCalledWith(10, 20, 600_000);
  await LiveTracker.getCoverage(10, 20, 5000);
  expect(native.getCoverage).toHaveBeenCalledWith(10, 20, 5000);

  await LiveTracker.clearArchive();
  await LiveTracker.getArchiveStats();
  expect(native.clearArchive).toHaveBeenCalled();
  expect(native.getArchiveStats).toHaveBeenCalled();
});

test('event subscriptions forward to native addListener and return subscription', () => {
  const locCb = jest.fn();
  const evtCb = jest.fn();
  const errCb = jest.fn();

  const locSub = LiveTracker.onLocation(locCb);
  const evtSub = LiveTracker.onEvent(evtCb);
  const errSub = LiveTracker.onSyncError(errCb);

  expect(native.addListener).toHaveBeenCalledWith('onLocation', locCb);
  expect(native.addListener).toHaveBeenCalledWith('onEvent', evtCb);
  expect(native.addListener).toHaveBeenCalledWith('onSyncError', errCb);

  expect(typeof locSub.remove).toBe('function');
  expect(typeof evtSub.remove).toBe('function');
  expect(typeof errSub.remove).toBe('function');
});
