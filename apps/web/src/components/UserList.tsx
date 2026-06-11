import { colorForUid } from '../lib/colors';
import { useUsers } from '../hooks/useUsers';

export function UserList({
  selected,
  onSelect,
}: {
  selected: string | null;
  onSelect: (uid: string) => void;
}) {
  const { users, loading, error } = useUsers();

  return (
    <div>
      <h3 style={{ margin: '0 0 8px' }}>Users</h3>
      {loading && <p style={{ color: '#888' }}>Loading…</p>}
      {error && <p style={{ color: '#e6194b' }}>Error: {error.message}</p>}
      {!loading && users.length === 0 && <p style={{ color: '#888' }}>No tracked users yet.</p>}
      <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {users.map(({ uid, identifier }) => {
          const checked = selected === uid;
          return (
            <li key={uid} style={{ marginBottom: 6 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                <input
                  type="radio"
                  name="user"
                  checked={checked}
                  onChange={() => onSelect(uid)}
                />
                <span
                  style={{
                    width: 12,
                    height: 12,
                    borderRadius: '50%',
                    background: colorForUid(uid),
                    display: 'inline-block',
                    flexShrink: 0,
                  }}
                />
                <span style={{ fontSize: 13, wordBreak: 'break-all' }}>{identifier}</span>
              </label>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
