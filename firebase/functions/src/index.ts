/**
 * expo-livetrack ingest endpoint.
 *
 * The expo-livetrack native module buffers location fixes + diagnostic events
 * on-device and uploads them in batches:
 *
 *   POST <this function URL>
 *   Authorization: Bearer <token>
 *   Content-Type: application/json
 *
 *   {
 *     "points": [{ id, u, l, g, t, s, acc, b, c, act, mock }, ...],
 *     "events": [{ id, u, e, t, l?, g?, b? }, ...]
 *   }
 *
 * It expects back:  { "acceptedIds": ["<id>", ...] }
 * and deletes every accepted row from its local buffer.
 *
 * Here, the Bearer token is the signed-in user's Firebase **ID token**. We
 * verify it, derive the uid, and fan the rows out into Realtime Database under
 * /tracks/{uid}/points/{id} and /tracks/{uid}/events/{id} using the Admin SDK
 * (which bypasses database.rules.json).
 */

import {setGlobalOptions} from "firebase-functions";
import {onRequest, type Request} from "firebase-functions/https";
import * as logger from "firebase-functions/logger";
import * as admin from "firebase-admin";

admin.initializeApp();

// Cap concurrent containers to mitigate runaway cost on traffic spikes.
setGlobalOptions({maxInstances: 10});

/** Pull the Bearer token out of the Authorization header, or null. */
function bearer(req: Request): string | null {
  const header = req.get("authorization") || req.get("Authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : null;
}

type Row = Record<string, unknown> & {id?: unknown; u?: unknown};

export const ingest = onRequest({cors: false, maxInstances: 10}, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({error: "method-not-allowed"});
    return;
  }

  const token = bearer(req);
  if (!token) {
    res.status(401).json({error: "missing-bearer-token"});
    return;
  }

  let uid: string;
  try {
    const decoded = await admin.auth().verifyIdToken(token);
    uid = decoded.uid;
  } catch (err) {
    logger.warn("ID token verification failed", (err as Error).message);
    res.status(401).json({error: "invalid-token"});
    return;
  }

  const body = (req.body || {}) as {points?: unknown; events?: unknown};
  const points: Row[] = Array.isArray(body.points) ? body.points : [];
  const events: Row[] = Array.isArray(body.events) ? body.events : [];

  // Build a single multi-location update so the whole batch lands atomically.
  const updates: Record<string, unknown> = {};
  const acceptedIds: string[] = [];

  for (const p of points) {
    if (!p || p.id == null) continue;
    // Defence-in-depth: a token only writes to its own subtree, and we ignore
    // any row claiming a different owner than the verified uid.
    if (p.u != null && p.u !== uid) continue;
    updates[`tracks/${uid}/points/${p.id}`] = p;
    acceptedIds.push(String(p.id));
  }

  for (const e of events) {
    if (!e || e.id == null) continue;
    if (e.u != null && e.u !== uid) continue;
    updates[`tracks/${uid}/events/${e.id}`] = e;
    acceptedIds.push(String(e.id));
  }

  try {
    if (acceptedIds.length > 0) {
      await admin.database().ref().update(updates);
    }
  } catch (err) {
    logger.error("RTDB write failed", (err as Error).message);
    res.status(500).json({error: "write-failed"});
    return;
  }

  logger.info(`ingest ok uid=${uid} points=${points.length} events=${events.length}`);
  res.status(200).json({acceptedIds});
});
