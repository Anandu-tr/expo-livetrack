import { Popup } from 'react-leaflet';
import type { TrackPoint } from '../types';

function fmtTime(ms: number): string {
  return new Date(ms).toLocaleString();
}

export function PointPopup({ point }: { point: TrackPoint }) {
  return (
    <Popup>
      <div style={{ fontSize: 12, lineHeight: 1.5 }}>
        <div><strong>{fmtTime(point.t)}</strong></div>
        <div>Lat/Lng: {point.lat.toFixed(6)}, {point.lng.toFixed(6)}</div>
        {point.speed != null && <div>Speed: {point.speed.toFixed(1)} m/s</div>}
        {point.acc != null && point.acc >= 0 && <div>Accuracy: {point.acc.toFixed(0)} m</div>}
        {point.battery != null && point.battery >= 0 && (
          <div>Battery: {point.battery}%{point.charging ? ' (charging)' : ''}</div>
        )}
        {point.activity && <div>Activity: {point.activity}</div>}
        {point.mock && <div style={{ color: '#e6194b' }}><strong>⚠ Mock location</strong></div>}
      </div>
    </Popup>
  );
}
