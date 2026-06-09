# Firebase backend for the expo-livetrack example

This directory holds the Firebase pieces that back the `example/` app:

- **`functions/index.js`** — an HTTPS Cloud Function `ingest` that the
  expo-livetrack module uploads to. It verifies the caller's Firebase **ID
  token** (sent as the upload `Bearer` token), then writes the batch into
  Realtime Database under `tracks/{uid}/points` and `tracks/{uid}/events`.
- **`database.rules.json`** — lets a signed-in user read only their own
  `tracks/{uid}` subtree; all writes go through the function (Admin SDK).
- **`firebase.json`** — wires the function source + database rules.

## Data flow

```
Google Sign-In ──> Firebase Auth (uid + idToken)
        │
        ▼
LiveTracker.start({ url: <ingest URL>, token: idToken, userId: uid })
        │  POST { points:[...], events:[...] }   Authorization: Bearer <idToken>
        ▼
ingest function ──verify idToken──> RTDB /tracks/{uid}/{points,events}
        │
        ▼  { acceptedIds:[...] }  ──> module deletes the synced rows
```

## One-time Firebase setup (project already exists)

1. **Register apps** in the Firebase console (Project settings → Your apps):
   - Android, package `com.example.expolivetrack` → download `google-services.json`
     → save to `example/google-services.json`.
   - iOS, bundle id `com.example.expolivetrack` → download `GoogleService-Info.plist`
     → save to `example/GoogleService-Info.plist`.
2. **Enable Google** sign-in: Auth → Sign-in method → Google → Enable.
3. **Create Realtime Database**: Build → Realtime Database → Create.
4. Grab the two IDs the app needs:
   - **Web client ID** — Auth → Sign-in method → Google → "Web SDK
     configuration", or Google Cloud → Credentials → "Web client (auto created
     by Google Service)". Put it in `example/App.tsx` → `WEB_CLIENT_ID`.
   - **iOS reversed client ID** — the `REVERSED_CLIENT_ID` value inside
     `GoogleService-Info.plist`. Put it in `example/app.json` →
     `@react-native-google-signin/google-signin` plugin `iosUrlScheme`.

## Deploy the function + rules

```bash
cd firebase
firebase use <your-project-id>      # if not already selected
npm --prefix functions install
firebase deploy --only functions,database
```

The deploy output prints the `ingest` URL, e.g.
`https://ingest-xxxx-uc.a.run.app` (Gen-2) or
`https://us-central1-<project>.cloudfunctions.net/ingest`.
Paste it into `example/App.tsx` → `TRACK_URL`.

## Placeholders to fill in

| Placeholder | File | Source |
| --- | --- | --- |
| `REPLACE_WITH_INGEST_FUNCTION_URL` | `example/App.tsx` (`TRACK_URL`) | `firebase deploy` output |
| `REPLACE_WITH_WEB_CLIENT_ID` | `example/App.tsx` (`WEB_CLIENT_ID`) | Firebase Web client ID |
| `REPLACE_WITH_REVERSED_CLIENT_ID` | `example/app.json` (google-signin `iosUrlScheme`) | `REVERSED_CLIENT_ID` in `GoogleService-Info.plist` |
| `google-services.json` | `example/` | Firebase Android app |
| `GoogleService-Info.plist` | `example/` | Firebase iOS app |

## Build & run the app

```bash
cd example
npm install
npx expo prebuild --clean      # regenerates ios/ + android/ with the new plugins
npx expo run:android           # or: npx expo run:ios   (device w/ Play services)
```

In the app: **Sign in with Google → Request Permissions → Start** (accept the
disclosure). Watch `tracks/<uid>/points` and `.../events` populate in the
Realtime Database console; the app's `bufferedCount` drains to 0 as rows are
acknowledged.

## Quick function smoke test

```bash
# No auth → 401
curl -i -X POST <INGEST_URL> -H 'Content-Type: application/json' -d '{}'

# With a real ID token (grab one in the app via auth().currentUser.getIdToken())
curl -i -X POST <INGEST_URL> \
  -H "Authorization: Bearer <ID_TOKEN>" \
  -H 'Content-Type: application/json' \
  -d '{"points":[{"id":"t1","u":"<uid>","l":1.1,"g":2.2,"t":1700000000000,"s":0,"acc":5,"b":80,"c":false,"act":"STILL","mock":false}],"events":[]}'
# → {"acceptedIds":["t1"]}
```

## Known limitation — token expiry

Firebase ID tokens expire after ~1 hour. The module captures the `token` at
`start()`, so after expiry uploads return 401. The example handles this in
`onSyncError`: on 401/403 it refreshes the token (`getIdToken(true)`) and
restarts the tracker. For a production app you'd likely use a longer-lived
custom token or App Check instead of a raw ID token.
