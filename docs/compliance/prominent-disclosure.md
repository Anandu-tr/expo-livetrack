# Prominent disclosure — background location

Apps that access location in the background must show a **prominent disclosure**
*before* requesting the permission, and obtain **affirmative in-app consent**
(Accept / Decline). The disclosure must:

1. State that the app **collects location data**.
2. State that collection happens **in the background** — i.e. *even when the app
   is closed or not in use*.
3. State the **purpose** of the collection.
4. Be an **affirmative, in-app** consent step (a clear "Allow / continue" vs.
   "No thanks"), shown **before** the OS permission prompt — **not** satisfied by
   a privacy-policy link alone.

Edit the bracketed parts to match your app and organisation. `[App name]` should
be your actual app name; `[purpose]` should describe the legitimate business use.

---

## A. In-app disclosure (shown before the permission prompt)

> **Title:** Background location
>
> **Body:** [App name] collects your location data to record your field route
> and distance travelled, **including in the background, even when the app is
> closed or not in use**. Your location is sent securely to your organisation’s
> backend and is used only for route and distance reporting for work. You can
> stop tracking at any time from the app.
>
> **Buttons:** `No thanks` (decline) · `Allow & continue` (affirmative consent)

Only call `LiveTracker.start(...)` / request the OS background-location permission
**after** the user taps the affirmative button. The example app implements exactly
this flow (`example/App.tsx`, the "Background location" `Modal`).

---

## B. Google Play Console — background-location declaration

Use this in the **App content → Location permissions** declaration and in the
Data Safety section narrative.

> [App name] is a field-staff app. It uses **background location** to
> continuously record the user’s route and distance travelled while they are
> working, **even when the app is closed or not in use**. This is core to the
> app’s purpose: organisations rely on accurate, gap-free route and distance data
> for visit verification and travel reporting. Foreground-only location is not
> sufficient because field staff keep the phone pocketed/locked between visits,
> and dropped points would corrupt the route and distance totals.
>
> Before any location permission is requested, the app shows an in-app prominent
> disclosure (collection of location data, that it occurs in the background even
> when the app is closed, and the purpose) and requires the user to affirmatively
> consent. Users can stop tracking at any time from within the app.

---

## C. iOS purpose strings (Info.plist, via the config plugin)

These are set through the plugin props (see the README). Keep them specific —
vague strings cause App Store rejection.

- **`NSLocationWhenInUseUsageDescription`:**
  "We use your location to record your field visits and route while you are using
  the app."
- **`NSLocationAlwaysAndWhenInUseUsageDescription`** (and the legacy
  **`NSLocationAlwaysUsageDescription`**):
  "We continue to record your location in the background, even when the app is
  closed, so your full route and distance travelled are captured for work
  reporting."
