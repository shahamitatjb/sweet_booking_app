'use client';

import { useEffect, useState } from 'react';
import type { Me } from './AdminShell';

/** Signed-in staff member, or null when the session is missing. `undefined` while loading. */
export function useMe(): Me | null | undefined {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  useEffect(() => {
    fetch('/api/staff/me', { credentials: 'include' })
      .then((r) => r.json())
      .then((j) => setMe(j.role ? j : null))
      .catch(() => setMe(null));
  }, []);
  return me;
}
