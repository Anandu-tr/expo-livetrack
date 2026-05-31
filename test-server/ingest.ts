import { Batch, IngestResult, LocationRecord, TrackerEvent } from './types';

const MAX_ACCURACY_M = 50;

function dedupeKey(r: { u: string; t: number }): string {
  return `${r.u}|${r.t}`;
}

/**
 * Pure ingest: validate + dedupe a batch against already-stored keys.
 *
 * Rules (mirror the production pipeline):
 *  - Drop location points with `acc > 50` (50 is kept). Events are always accepted.
 *  - Keep the `mock` tag as-is (no rewriting).
 *  - Dedupe points by (u, t); first wins. `existingKeys` carries keys already in
 *    the store so duplicates across batches are also rejected.
 *
 * Returns the acceptedIds (point ids + event ids) and the accepted records.
 * This function does not mutate `existingKeys` or any store.
 */
export function ingest(batch: Batch, existingKeys: Set<string>): IngestResult {
  const points: LocationRecord[] = Array.isArray(batch.points) ? batch.points : [];
  const events: TrackerEvent[] = Array.isArray(batch.events) ? batch.events : [];

  const acceptedPoints: LocationRecord[] = [];
  // Local copy so within-batch dedupe works without mutating the caller's set.
  const seen = new Set<string>(existingKeys);

  for (const p of points) {
    if (typeof p.acc === 'number' && p.acc > MAX_ACCURACY_M) {
      continue; // drop low-quality fix
    }
    const key = dedupeKey(p);
    if (seen.has(key)) {
      continue; // duplicate (u,t) -> first wins
    }
    seen.add(key);
    acceptedPoints.push(p); // mock tag kept as-is
  }

  // Events are always accepted.
  const acceptedEvents: TrackerEvent[] = events.slice();

  const acceptedIds = [
    ...acceptedPoints.map((p) => p.id),
    ...acceptedEvents.map((e) => e.id),
  ];

  return {
    acceptedIds,
    accepted: { points: acceptedPoints, events: acceptedEvents },
  };
}

export { dedupeKey, MAX_ACCURACY_M };
