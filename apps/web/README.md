# LiveTrack Web — OpenStreetMap Route Viewer

A standalone React + Vite dashboard that visualizes tracked users' routes on an
OpenStreetMap map, reading live from the `expo-livetrack` Firebase Realtime
Database (`/tracks/{uid}/points`).

## Features

- **User list** — enumerated live from `/tracks` (multi-select with per-user colors).
- **Route polyline** — captured points connected in timestamp order.
- **Live updates** — routes extend in real time as new points arrive (RTDB `onValue`).
- **Time-range filter** — last hour / today / last 7d / all / custom window.
- **Point popups** — click a point for time, speed, accuracy, battery, activity, mock flag.
- **Multi-user overlay** — several routes at once, each in a distinct color.

## Run

```bash
npm install
cp .env.example .env   # defaults point at the simconnect-e111e project
npm run dev
```

Then open the printed Vite URL.

## Access note

Reading multiple users requires the RTDB rules to allow it. For development this
repo's `firebase/database.rules.json` sets `tracks/.read: true` (open read).
**This is not production-safe** — restrict to an admin/viewer UID before deploying.
Deploy rules with `firebase deploy --only database` from the `firebase/` folder.

## Config

Env vars (see `.env.example`): `VITE_FB_API_KEY`, `VITE_FB_DB_URL`, `VITE_FB_PROJECT_ID`.
