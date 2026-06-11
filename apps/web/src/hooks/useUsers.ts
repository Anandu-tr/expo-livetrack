import { useEffect, useState } from 'react';
import { onValue, ref, query, orderByKey } from 'firebase/database';
import { db } from '../firebase';

export interface TrackedUser {
  uid: string; // RTDB node key
  identifier: string; // human-facing label: name → email → phone → uid
}

interface Profile {
  displayName?: string | null;
  email?: string | null;
  phoneNumber?: string | null;
}

// Identity profiles are written to /users/{uid} by the ingest Cloud Function
// from the verified ID token's claims. Prefer name, then email, then phone.
function labelFor(uid: string, p?: Profile): string {
  return p?.displayName || p?.email || p?.phoneNumber || uid;
}

// Subscribes to /tracks (the canonical list of users with data) and /users
// (their identity profiles), merging them into a labelled user list.
export function useUsers(): { users: TrackedUser[]; loading: boolean; error?: Error } {
  const [uids, setUids] = useState<string[]>([]);
  const [profiles, setProfiles] = useState<Record<string, Profile>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error>();

  useEffect(() => {
    const tracksQuery = query(ref(db, 'tracks'), orderByKey());
    const unsubTracks = onValue(
      tracksQuery,
      (snap) => {
        const keys: string[] = [];
        snap.forEach((child) => {
          if (child.key) keys.push(child.key);
        });
        setUids(keys);
        setLoading(false);
      },
      (err) => {
        setError(err);
        setLoading(false);
      },
    );

    const unsubUsers = onValue(ref(db, 'users'), (snap) => {
      setProfiles((snap.val() as Record<string, Profile>) ?? {});
    });

    return () => {
      unsubTracks();
      unsubUsers();
    };
  }, []);

  const users: TrackedUser[] = uids.map((uid) => ({
    uid,
    identifier: labelFor(uid, profiles[uid]),
  }));

  return { users, loading, error };
}
