import request from 'supertest';
import { createApp, AckShape } from '../server';

const TOKEN = 'test-token';

/**
 * The ack-shape switch exists to reproduce a production failure: devices
 * re-uploading the identical batch forever because the client could not read the
 * server's acknowledgement, while every response was an HTTP 2xx.
 *
 * These tests pin the wire output of each shape. They are the fixture the native
 * uploader is exercised against — `canonical` must stay byte-compatible with the
 * documented contract, and the rest must stay recognisably broken in their own
 * distinct way.
 */

/** One clean point + one event, both of which the ingest rules always accept. */
function makeBatch() {
  return {
    points: [
      { id: '1043', u: 'user1', l: 12.9, g: 77.5, t: 1000, s: 1.4, acc: 8, b: 73, c: false, act: 'walking', mock: false },
    ],
    events: [{ id: '1044', u: 'user1', e: 'HEARTBEAT', t: 2000, b: 88 }],
  };
}

async function post(app: ReturnType<typeof createApp>) {
  return request(app)
    .post('/locations/batch')
    .set('Authorization', `Bearer ${TOKEN}`)
    .send(makeBatch());
}

describe('ack shapes', () => {
  it('defaults to canonical when no shape is given', async () => {
    const res = await post(createApp());
    expect(res.status).toBe(200);
    expect(res.body.acceptedIds.slice().sort()).toEqual(['1043', '1044']);
  });

  it('ignores an unrecognised ACK_SHAPE and falls back to canonical', async () => {
    const prev = process.env.ACK_SHAPE;
    process.env.ACK_SHAPE = 'not-a-shape';
    try {
      const res = await post(createApp());
      expect(res.status).toBe(200);
      expect(res.body.acceptedIds.slice().sort()).toEqual(['1043', '1044']);
    } finally {
      if (prev === undefined) delete process.env.ACK_SHAPE;
      else process.env.ACK_SHAPE = prev;
    }
  });

  it('empty: 201 with no body', async () => {
    const res = await post(createApp('empty'));
    expect(res.status).toBe(201);
    expect(res.text).toBe('');
  });

  it('envelope: ids nested under data', async () => {
    const res = await post(createApp('envelope'));
    expect(res.status).toBe(201);
    expect(res.body).toEqual({ success: true, data: { acceptedIds: ['1043', '1044'] } });
  });

  it('objects: ids wrapped in objects', async () => {
    const res = await post(createApp('objects'));
    expect(res.status).toBe(201);
    expect(res.body.acceptedIds).toEqual([{ id: '1043' }, { id: '1044' }]);
  });

  it('serverIds: same count, none of the ids we sent', async () => {
    const res = await post(createApp('serverIds'));
    expect(res.status).toBe(201);
    // This is the shape that makes "all ids ACKed" true while nothing matches.
    expect(res.body.acceptedIds).toHaveLength(2);
    expect(res.body.acceptedIds).not.toContain('1043');
    expect(res.body.acceptedIds).not.toContain('1044');
  });

  it('serverIds: keeps issuing fresh ids across batches', async () => {
    const app = createApp('serverIds');
    const first = await post(app);
    const second = await post(app);
    // Second batch dedupes to just the event, but the ids must still not collide.
    expect(second.body.acceptedIds.every((id: string) => !first.body.acceptedIds.includes(id)))
      .toBe(true);
  });

  it('zero: accepted, acknowledged nothing', async () => {
    const res = await post(createApp('zero'));
    expect(res.status).toBe(201);
    expect(res.body).toEqual({ acceptedIds: [] });
  });

  it('every shape still returns a 2xx — that is what made the bug silent', async () => {
    const shapes: AckShape[] = ['canonical', 'empty', 'envelope', 'objects', 'serverIds', 'zero'];
    for (const shape of shapes) {
      const res = await post(createApp(shape));
      expect(res.status).toBeGreaterThanOrEqual(200);
      expect(res.status).toBeLessThan(300);
    }
  });
});
