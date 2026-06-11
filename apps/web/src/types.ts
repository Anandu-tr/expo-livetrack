// Wire schema as stored in Firebase RTDB under /tracks/{uid}/points/{id}.
// Mirrors LocationRecord in the root module's src/LiveTrack.types.ts, plus the
// `u` (userId) field that the ingest function stamps on each stored record.
export interface RawPoint {
  u?: string;
  l: number; // latitude
  g: number; // longitude
  t: number; // epoch ms
  s?: number; // speed (m/s)
  acc?: number; // accuracy (m); -1 = unknown
  b?: number; // battery %
  c?: boolean; // charging
  act?: string; // activity type
  mock?: boolean; // mock/spoofed location detected
}

// Normalized point used throughout the UI (lat/lng spelled out, id attached).
export interface TrackPoint {
  id: string;
  lat: number;
  lng: number;
  t: number;
  speed?: number;
  acc?: number;
  battery?: number;
  charging?: boolean;
  activity?: string;
  mock?: boolean;
}

export type EventType =
  | 'LOCATION_OFF'
  | 'LOCATION_ON'
  | 'PERMISSION_REVOKED'
  | 'PERMISSION_GRANTED'
  | 'MOCK_DETECTED'
  | 'BATTERY_OPT_ON'
  | 'HEARTBEAT';

export function normalizePoint(id: string, raw: RawPoint): TrackPoint {
  return {
    id,
    lat: raw.l,
    lng: raw.g,
    t: raw.t,
    speed: raw.s,
    acc: raw.acc,
    battery: raw.b,
    charging: raw.c,
    activity: raw.act,
    mock: raw.mock,
  };
}
