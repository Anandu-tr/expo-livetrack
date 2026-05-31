import express, { Express, Request, Response, NextFunction } from 'express';
import { ingest, dedupeKey } from './ingest';
import { Batch, LocationRecord, TrackerEvent } from './types';

interface Store {
  points: LocationRecord[];
  events: TrackerEvent[];
  keys: Set<string>; // (u,t) keys of stored points for cross-batch dedupe
}

function newStore(): Store {
  return { points: [], events: [], keys: new Set<string>() };
}

// Dummy auth: any non-empty Bearer token passes; missing/empty -> 401.
function requireBearer(req: Request, res: Response, next: NextFunction) {
  const header = req.header('authorization') || '';
  const match = /^Bearer\s+(.+)$/i.exec(header);
  const token = match ? match[1].trim() : '';
  if (!token) {
    res.status(401).json({ error: 'missing or empty bearer token' });
    return;
  }
  next();
}

export function createApp(): Express {
  const app = express();
  app.use(express.json());

  const store = newStore();

  app.post('/locations/batch', requireBearer, (req: Request, res: Response) => {
    const body = (req.body || {}) as Partial<Batch>;
    const batch: Batch = {
      points: Array.isArray(body.points) ? body.points : [],
      events: Array.isArray(body.events) ? body.events : [],
    };

    const result = ingest(batch, store.keys);

    // Persist accepted records + register their (u,t) keys for future batches.
    for (const p of result.accepted.points) {
      store.points.push(p);
      store.keys.add(dedupeKey(p));
    }
    for (const e of result.accepted.events) {
      store.events.push(e);
    }

    res.status(200).json({ acceptedIds: result.acceptedIds });
  });

  app.get('/debug/received', requireBearer, (_req: Request, res: Response) => {
    res.status(200).json({ points: store.points, events: store.events });
  });

  app.post('/debug/reset', requireBearer, (_req: Request, res: Response) => {
    store.points = [];
    store.events = [];
    store.keys = new Set<string>();
    res.status(200).json({ ok: true });
  });

  return app;
}

export function start(port = 8787): ReturnType<Express['listen']> {
  const app = createApp();
  return app.listen(port, () => {
    // eslint-disable-next-line no-console
    console.log(`[test-server] dummy ingest listening on http://localhost:${port}`);
  });
}

// Allow manual run: `npx ts-node server.ts`
if (require.main === module) {
  start(Number(process.env.PORT) || 8787);
}
