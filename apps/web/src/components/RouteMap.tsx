import { useEffect, useMemo, useState } from 'react';
import { MapContainer, TileLayer, Polyline, CircleMarker, useMap } from 'react-leaflet';
import { LatLngBounds, type LatLngExpression } from 'leaflet';
import { useUserPoints, type TimeRange } from '../hooks/useUserPoints';
import { colorForUid } from '../lib/colors';
import { PointPopup } from './PointPopup';
import type { TrackPoint } from '../types';

const DEFAULT_CENTER: LatLngExpression = [20, 0];

// Renders one user's route (polyline + per-point markers) and reports its
// positions upward so the map can fit bounds across all selected users.
function UserRoute({
  uid,
  range,
  onPoints,
}: {
  uid: string;
  range: TimeRange;
  onPoints: (uid: string, points: TrackPoint[]) => void;
}) {
  const points = useUserPoints(uid, range);
  const color = colorForUid(uid);

  useEffect(() => {
    onPoints(uid, points);
    return () => onPoints(uid, []);
  }, [uid, points, onPoints]);

  const positions = points.map((p) => [p.lat, p.lng] as LatLngExpression);

  return (
    <>
      {positions.length > 1 && <Polyline positions={positions} pathOptions={{ color, weight: 4 }} />}
      {points.map((p) => (
        <CircleMarker
          key={p.id}
          center={[p.lat, p.lng]}
          radius={5}
          pathOptions={{ color, fillColor: color, fillOpacity: 0.9 }}
        >
          <PointPopup point={p} />
        </CircleMarker>
      ))}
    </>
  );
}

// Pans/zooms the map to fit all currently rendered points whenever they change.
function FitBounds({ allPoints }: { allPoints: TrackPoint[] }) {
  const map = useMap();
  useEffect(() => {
    if (allPoints.length === 0) return;
    const bounds = new LatLngBounds(allPoints.map((p) => [p.lat, p.lng]));
    map.fitBounds(bounds, { padding: [40, 40], maxZoom: 17 });
  }, [allPoints, map]);
  return null;
}

export function RouteMap({ selectedUids, range }: { selectedUids: string[]; range: TimeRange }) {
  const [pointsByUid, setPointsByUid] = useState<Record<string, TrackPoint[]>>({});

  const onPoints = useMemo(
    () => (uid: string, points: TrackPoint[]) =>
      setPointsByUid((prev) => ({ ...prev, [uid]: points })),
    [],
  );

  const allPoints = useMemo(
    () => selectedUids.flatMap((uid) => pointsByUid[uid] ?? []),
    [selectedUids, pointsByUid],
  );

  return (
    <MapContainer center={DEFAULT_CENTER} zoom={2} style={{ height: '100%', width: '100%' }}>
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
      />
      {selectedUids.map((uid) => (
        <UserRoute key={uid} uid={uid} range={range} onPoints={onPoints} />
      ))}
      <FitBounds allPoints={allPoints} />
    </MapContainer>
  );
}
