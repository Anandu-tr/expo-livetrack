import { useEffect, useState } from 'react';
import { onValue, ref, query, orderByChild, startAt, endAt } from 'firebase/database';
import { db } from '../firebase';
import { normalizePoint, type RawPoint, type TrackPoint } from '../types';

export interface TimeRange {
  startMs: number;
  endMs: number;
}

// Subscribes to /tracks/{uid}/points, filtered to the given time range, and
// returns the points sorted ascending by timestamp (route order). Re-renders
// live as new points arrive.
export function useUserPoints(uid: string, range: TimeRange): TrackPoint[] {
  const [points, setPoints] = useState<TrackPoint[]>([]);

  useEffect(() => {
    const pointsQuery = query(
      ref(db, `tracks/${uid}/points`),
      orderByChild('t'),
      startAt(range.startMs),
      endAt(range.endMs),
    );
    const unsub = onValue(pointsQuery, (snap) => {
      const next: TrackPoint[] = [];
      snap.forEach((child) => {
        const raw = child.val() as RawPoint;
        if (child.key && typeof raw?.l === 'number' && typeof raw?.g === 'number') {
          next.push(normalizePoint(child.key, raw));
        }
      });
      next.sort((a, b) => a.t - b.t);
      setPoints(next);
    });
    return () => unsub();
  }, [uid, range.startMs, range.endMs]);

  return points;
}
