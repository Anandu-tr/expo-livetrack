# Demo video script — Play background-location declaration

Google Play requires a short video for the background-location declaration that
shows the **prominent disclosure → permission grant → the feature using background
location**, in that order, on a real device. Keep it ~45–90 seconds, no cuts that
hide a required step, and make sure the on-screen text is readable.

Replace `[App name]` with your app name before recording.

---

## Setup (before recording)

- Fresh install (or app data cleared) so the disclosure and OS permission prompts
  actually appear.
- Location services ON; the test ingest server running if you want to show points
  flowing (optional).
- Screen recorder capturing the whole flow in one take.

---

## Scene 1 — Launch & in-app prominent disclosure (0:00–0:20)

- Open [App name] and navigate to the tracking screen.
- Tap **Start**.
- The **in-app prominent disclosure** modal appears. Pause so it's fully readable.
  Voice-over / caption: *"Before we ask for any permission, the app explains that
  it collects location data, that this happens in the background even when the app
  is closed, and why."*
- Tap **Allow & continue**.

## Scene 2 — OS permission prompt & background grant (0:20–0:40)

- The Android location permission dialog appears. Tap **While using the app**.
- When prompted for background access ("Allow all the time"), open settings if
  needed and select **Allow all the time**.
- Caption: *"The user grants background ('Allow all the time') location access."*

## Scene 3 — Feature using background location (0:40–1:15)

- Show tracking is active: the in-app status shows `tracking: true`, and the
  **persistent foreground-service notification** is visible in the shade.
- Press the **Home** button to send the app to the background (or lock the screen).
- Move with the device (or simulate movement) so new fixes are captured while the
  app is backgrounded.
- Re-open the app: show the route/last fix and the event log updating — proving
  points were collected while the app was in the background.
- Caption: *"Location is recorded continuously in the background, even with the
  app closed, to build the field route and distance."*

## Scene 4 — User control (1:15–end)

- Tap **Stop**. Show `tracking: false` and the foreground notification clearing.
- Caption: *"The user can stop background tracking at any time."*

---

## Checklist before submitting

- [ ] Disclosure shown **before** the OS permission prompt.
- [ ] Disclosure mentions: location data, background / "even when closed", purpose.
- [ ] Background ("Allow all the time") permission granted on camera.
- [ ] Background usage demonstrated (app backgrounded/locked while points collect).
- [ ] Persistent foreground notification visible during tracking.
- [ ] User can stop tracking, shown on camera.
