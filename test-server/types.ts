// Wire-contract types for the dummy ingestion server.
// NOTE: these intentionally include a client-generated `id` on each record,
// matching what the native uploader sends (the package's src/ types do not
// carry `id` yet). This server is self-contained dev-only tooling and does not
// import from the package's src/.

export interface LocationRecord {
  id: string;
  u: string;
  l: number;
  g: number;
  t: number;
  s: number;
  acc: number;
  b: number;
  c: boolean;
  act: string;
  mock: boolean;
}

export interface TrackerEvent {
  id: string;
  u: string;
  e: string;
  t: number;
  l?: number;
  g?: number;
  b?: number;
}

export interface Batch {
  points: LocationRecord[];
  events: TrackerEvent[];
}

export interface IngestResult {
  acceptedIds: string[];
  accepted: {
    points: LocationRecord[];
    events: TrackerEvent[];
  };
}
