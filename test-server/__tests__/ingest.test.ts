import request from 'supertest';
import { createApp } from '../server';
import { Batch } from '../types';

const TOKEN = 'dummy-token-abc';

function makeBatch(): Batch {
  return {
    points: [
      // pointA: low accuracy quality (acc=80 > 50) -> dropped
      {
        id: 'pA',
        u: 'user1',
        l: 12.9,
        g: 77.5,
        t: 1000,
        s: 0,
        acc: 80,
        b: 90,
        c: false,
        act: 'still',
        mock: false,
      },
      // pointB: acc=12 -> accepted
      {
        id: 'pB',
        u: 'user1',
        l: 12.91,
        g: 77.51,
        t: 2000,
        s: 1,
        acc: 12,
        b: 89,
        c: true,
        act: 'walking',
        mock: false,
      },
      // pointC: duplicate (u,t) of pointB -> dropped (first wins)
      {
        id: 'pC',
        u: 'user1',
        l: 99.99,
        g: 99.99,
        t: 2000,
        s: 5,
        acc: 10,
        b: 88,
        c: false,
        act: 'walking',
        mock: true,
      },
    ],
    events: [
      // event: always accepted
      {
        id: 'eX',
        u: 'user1',
        e: 'HEARTBEAT',
        t: 2500,
        b: 88,
      },
    ],
  };
}

describe('POST /locations/batch', () => {
  let app: ReturnType<typeof createApp>;

  beforeEach(async () => {
    app = createApp();
    await request(app).post('/debug/reset').set('Authorization', `Bearer ${TOKEN}`);
  });

  it('drops high-acc points, dedupes (u,t), accepts events, returns acceptedIds', async () => {
    const res = await request(app)
      .post('/locations/batch')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send(makeBatch());

    expect(res.status).toBe(200);

    // pB (acc=12) + eX accepted; pA (acc=80) dropped; pC dup-of-pB dropped.
    const expected = ['pB', 'eX'];
    expect(res.body.acceptedIds.slice().sort()).toEqual(expected.slice().sort());

    // /debug/received reflects exactly the stored records.
    const dbg = await request(app)
      .get('/debug/received')
      .set('Authorization', `Bearer ${TOKEN}`);
    expect(dbg.status).toBe(200);
    expect(dbg.body.points.map((p: any) => p.id).sort()).toEqual(['pB']);
    expect(dbg.body.events.map((e: any) => e.id).sort()).toEqual(['eX']);
    // mock tag kept as-is on the accepted point.
    expect(dbg.body.points[0].mock).toBe(false);
  });

  it('rejects with 401 when Authorization header is missing', async () => {
    const res = await request(app).post('/locations/batch').send(makeBatch());
    expect(res.status).toBe(401);
  });

  it('rejects with 401 when Authorization is an empty bearer token', async () => {
    const res = await request(app)
      .post('/locations/batch')
      .set('Authorization', 'Bearer ')
      .send(makeBatch());
    expect(res.status).toBe(401);
  });

  it('dedupes across batches (same (u,t) in a second POST is rejected)', async () => {
    await request(app)
      .post('/locations/batch')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send(makeBatch());

    // Re-send pB's (u,t) in a new batch -> should NOT be accepted again.
    const second: Batch = {
      points: [
        {
          id: 'pB2',
          u: 'user1',
          l: 12.91,
          g: 77.51,
          t: 2000,
          s: 1,
          acc: 5,
          b: 80,
          c: true,
          act: 'walking',
          mock: false,
        },
      ],
      events: [],
    };
    const res = await request(app)
      .post('/locations/batch')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send(second);
    expect(res.status).toBe(200);
    expect(res.body.acceptedIds).toEqual([]);
  });
});
