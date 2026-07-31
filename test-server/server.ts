import express, { Express, Request, Response, NextFunction } from 'express';
import { ingest, dedupeKey } from './ingest';
import { Batch, LocationRecord, TrackerEvent } from './types';

interface Store {
  points: LocationRecord[];
  events: TrackerEvent[];
  keys: Set<string>; // (u,t) keys of stored points for cross-batch dedupe
  serverId: number; // monotonic PK generator for the `serverIds` ack shape
}

function newStore(): Store {
  return { points: [], events: [], keys: new Set<string>(), serverId: 1000 };
}

/**
 * How the server reports which rows it accepted.
 *
 * `canonical` is the documented contract and the default, so existing tests and
 * the happy path are untouched. The rest reproduce ack shapes observed in the
 * wild that a strict client parser cannot read — each one made real devices
 * re-upload the same batch forever, because the uploader treated "2xx with zero
 * parseable ids" as success. They exist so that failure mode stays testable.
 */
export type AckShape =
  | 'canonical' // 200 { acceptedIds: ["1", ...] }        — the documented contract
  | 'empty' // 201 with no body at all
  | 'envelope' // 201 { success, data: { acceptedIds } }
  | 'objects' // 201 { acceptedIds: [{ id: "1" }, ...] }
  | 'serverIds' // 201 { acceptedIds: [<server PKs>] }    — ids we never sent
  | 'zero'; // 201 { acceptedIds: [] }                    — accepted, acked nothing

const ACK_SHAPES: readonly AckShape[] = [
  'canonical', 'empty', 'envelope', 'objects', 'serverIds', 'zero',
];

function resolveAckShape(raw: string | undefined): AckShape {
  return ACK_SHAPES.includes(raw as AckShape) ? (raw as AckShape) : 'canonical';
}

/** Write the ack response for `shape`. Returns nothing; sends the response. */
function sendAck(res: Response, shape: AckShape, acceptedIds: string[], store: Store): void {
  switch (shape) {
    case 'empty':
      res.status(201).end();
      return;
    case 'envelope':
      res.status(201).json({ success: true, data: { acceptedIds } });
      return;
    case 'objects':
      res.status(201).json({ acceptedIds: acceptedIds.map((id) => ({ id })) });
      return;
    case 'serverIds':
      // Echo OUR primary keys instead of the client's row ids. The count matches,
      // the values do not — which is why "all ids ACKed" can be true and the
      // buffer still never drains.
      res.status(201).json({
        acceptedIds: acceptedIds.map(() => String((store.serverId += 1))),
      });
      return;
    case 'zero':
      res.status(201).json({ acceptedIds: [] });
      return;
    case 'canonical':
    default:
      res.status(200).json({ acceptedIds });
  }
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

/**
 * @param ackShape how to report accepted rows. Defaults to `ACK_SHAPE` from the
 *   environment, else `canonical` — so nothing changes unless you opt in.
 */
export function createApp(ackShape?: AckShape): Express {
  const app = express();
  app.use(express.json());

  const store = newStore();
  const shape = ackShape ?? resolveAckShape(process.env.ACK_SHAPE);

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

    sendAck(res, shape, result.acceptedIds, store);
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

export function start(port = 8787, ackShape?: AckShape): ReturnType<Express['listen']> {
  const shape = ackShape ?? resolveAckShape(process.env.ACK_SHAPE);
  const app = createApp(shape);
  return app.listen(port, () => {
    // eslint-disable-next-line no-console
    console.log(
      `[test-server] dummy ingest listening on http://localhost:${port} (ACK_SHAPE=${shape})`,
    );
  });
}

// Allow manual run: `npx ts-node server.ts`
if (require.main === module) {
  start(Number(process.env.PORT) || 8787);
}
