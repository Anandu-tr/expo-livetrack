import { useState } from 'react';
import { UserList } from './components/UserList';
import { TimeRangeFilter, rangeForPreset } from './components/TimeRangeFilter';
import { RouteMap } from './components/RouteMap';
import type { TimeRange } from './hooks/useUserPoints';

export default function App() {
  const [selected, setSelected] = useState<string | null>(null);
  const [range, setRange] = useState<TimeRange>(() => rangeForPreset('today', Date.now()));

  return (
    <div style={{ display: 'flex', height: '100vh', fontFamily: 'system-ui, sans-serif' }}>
      <aside
        style={{
          width: 300,
          padding: 16,
          borderRight: '1px solid #e0e0e0',
          overflowY: 'auto',
          flexShrink: 0,
        }}
      >
        <h2 style={{ marginTop: 0 }}>LiveTrack</h2>
        <p style={{ fontSize: 12, color: '#888', marginTop: -8 }}>OpenStreetMap route viewer</p>
        <UserList selected={selected} onSelect={setSelected} />
        <TimeRangeFilter range={range} onChange={setRange} />
      </aside>
      <main style={{ flex: 1 }}>
        <RouteMap selectedUids={selected ? [selected] : []} range={range} />
      </main>
    </div>
  );
}
