import { useState } from 'react';
import type { TimeRange } from '../hooks/useUserPoints';

type Preset = 'hour' | 'today' | 'week' | 'all' | 'custom';

const HOUR = 3600_000;
const DAY = 24 * HOUR;

// Builds a TimeRange for a preset relative to `now` (passed in so the parent
// controls when "now" is sampled).
export function rangeForPreset(preset: Exclude<Preset, 'custom'>, now: number): TimeRange {
  switch (preset) {
    case 'hour':
      return { startMs: now - HOUR, endMs: now };
    case 'today': {
      const start = new Date(now);
      start.setHours(0, 0, 0, 0);
      return { startMs: start.getTime(), endMs: now };
    }
    case 'week':
      return { startMs: now - 7 * DAY, endMs: now };
    case 'all':
      return { startMs: 0, endMs: now };
  }
}

function toLocalInput(ms: number): string {
  const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000);
  return d.toISOString().slice(0, 16);
}

export function TimeRangeFilter({
  range,
  onChange,
}: {
  range: TimeRange;
  onChange: (r: TimeRange) => void;
}) {
  const [preset, setPreset] = useState<Preset>('today');

  function applyPreset(p: Preset) {
    setPreset(p);
    if (p !== 'custom') onChange(rangeForPreset(p, Date.now()));
  }

  return (
    <div style={{ marginTop: 16 }}>
      <h3 style={{ margin: '0 0 8px' }}>Time range</h3>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {(['hour', 'today', 'week', 'all', 'custom'] as Preset[]).map((p) => (
          <button
            key={p}
            onClick={() => applyPreset(p)}
            style={{
              padding: '4px 8px',
              fontSize: 12,
              borderRadius: 4,
              border: '1px solid #ccc',
              background: preset === p ? '#4363d8' : '#fff',
              color: preset === p ? '#fff' : '#333',
              cursor: 'pointer',
            }}
          >
            {p === 'hour' ? 'Last hour' : p === 'today' ? 'Today' : p === 'week' ? 'Last 7d' : p === 'all' ? 'All' : 'Custom'}
          </button>
        ))}
      </div>
      {preset === 'custom' && (
        <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 6, fontSize: 12 }}>
          <label>
            From{' '}
            <input
              type="datetime-local"
              value={toLocalInput(range.startMs)}
              onChange={(e) => onChange({ ...range, startMs: new Date(e.target.value).getTime() })}
            />
          </label>
          <label>
            To{' '}
            <input
              type="datetime-local"
              value={toLocalInput(range.endMs)}
              onChange={(e) => onChange({ ...range, endMs: new Date(e.target.value).getTime() })}
            />
          </label>
        </div>
      )}
    </div>
  );
}
