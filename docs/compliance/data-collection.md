# Data collection — Data Safety form & Apple privacy labels

This is the exact set of data `expo-livetrack` collects and transmits, for use in
the **Google Play Data Safety** form and the **Apple App Privacy ("nutrition
label")** questionnaire. The list is taken directly from the package's data model
(`src/LiveTrack.types.ts`).

## Summary

- **Data type collected:** precise location, plus device/diagnostic signals tied
  to each location fix.
- **Collected:** yes. **Shared with third parties:** no (sent only to the
  backend you configure via `start({ url })`).
- **Transmission:** over the network to the configured backend. Configure an
  **HTTPS** endpoint in production.
- **Purpose:** field-staff route and distance tracking (visit verification,
  travel reporting). Not used for advertising.
- **Linked to the user:** yes — each record carries the app-supplied `userId`.
- **User control:** the user consents via an in-app prominent disclosure before
  collection and can stop tracking at any time in the app.

---

## Per-location-fix fields (`LocationRecord`)

| Field | Meaning | Data Safety / Apple category |
| --- | --- | --- |
| latitude (`l`) | GPS latitude | Location → **Precise location** |
| longitude (`g`) | GPS longitude | Location → **Precise location** |
| timestamp (`t`) | Epoch ms of the fix | App activity / diagnostics |
| speed (`s`) | Speed in m/s | App activity (derived from location) |
| accuracy (`acc`) | Horizontal accuracy in metres | Diagnostics (location quality) |
| battery % (`b`) | Battery level | Device or other IDs / Diagnostics |
| charging (`c`) | Whether the device is charging | Diagnostics |
| activity type (`act`) | Detected activity (e.g. walking, vehicle) | App activity |
| mock flag (`mock`) | Whether the fix came from a mock/spoofed provider | Diagnostics (anti-tamper) |

Each uploaded record is also stamped with a client-generated `id` (for
acknowledgement) and the `userId` (`u`).

---

## Tier-2 event types (`TrackerEvent.e`)

Diagnostic / lifecycle events are uploaded alongside location fixes. They carry the
event type, a timestamp, and optionally the last-known lat/lng/battery.

| Event | What it records |
| --- | --- |
| `LOCATION_OFF` | Device location services turned off |
| `LOCATION_ON` | Device location services turned back on |
| `PERMISSION_REVOKED` | Location permission revoked |
| `PERMISSION_GRANTED` | Location permission granted |
| `MOCK_DETECTED` | A mock/spoofed location provider was detected |
| `BATTERY_OPT_ON` | Battery optimisation active (may freeze tracking) |
| `HEARTBEAT` | Periodic liveness signal |

These events fall under **Diagnostics / App activity** and support data integrity
and tamper-evidence (e.g. detecting that a user disabled location or used a mock
GPS provider).

---

## Crash-reporter payloads (opt-in)

When the host enables `diagnostics: { crashlytics: true }`, operational failures are
recorded as non-fatals. These go to **your** crash reporter, not to any endpoint the
package chooses, and are off by default.

| Attribute | What it contains | Notes |
| --- | --- | --- |
| `status`, `host` | HTTP status and the **hostname** of your configured `url` | No path, no query string |
| `rowsSent`, `bufferedCount`, `parsedCount` | Counts only | No location values |
| `sampleSent`, `sampleParsed`, `sampleIds` | Up to 10 local SQLite row ids | Opaque integers; not user identifiers |
| `bodySnippet` | First 300 chars of your server's **response** body, whitespace-collapsed | Emitted only on a 2xx the client could not read (`upload-ack-empty` / `upload-ack-foreign`) |

`bodySnippet` exists so a non-conforming ack format is diagnosable rather than
silently stalling the buffer. It captures your own server's response — if that
response embeds personal data, it will be included, so review what your ingest
endpoint returns before enabling `diagnostics.crashlytics`.

No latitude, longitude, token, or user id is placed in any diagnostic attribute.

---

## Declaration notes

- **Do not** declare advertising or analytics-vendor purposes — none of this data
  is used for those.
- Declare location as **collected and linked to the user**, with purpose **app
  functionality** (route/distance tracking).
- If your backend retains data, reflect your retention/deletion policy in the
  store forms; the package itself only buffers locally until the server
  acknowledges each record, then deletes the acknowledged records.
