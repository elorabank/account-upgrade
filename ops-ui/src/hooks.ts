import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { get } from './api';
import type { Me } from './types';

export function useFetch<T>(url: string | null) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!url) return;
    let current = true;
    setLoading(true);
    setError(null);
    get<T>(url)
      .then(d => current && setData(d))
      .catch(e => current && setError(e.message))
      .finally(() => current && setLoading(false));
    return () => {
      current = false;
    };
  }, [url, tick]);

  const reload = useCallback(() => setTick(t => t + 1), []);
  return { data, error, loading, reload };
}

export const MeContext = createContext<Me | null>(null);

/** What the signed-in person may see and do. The services enforce the same rules; this only hides what would fail. */
export function useAccess() {
  const me = useContext(MeContext);
  const has = (...roles: string[]) => !!me && roles.some(r => me.roles.includes(r));
  return {
    me,
    readOps: has('AU_OPS_ADMIN', 'AU_OPS_VIEWER', 'AU_SUPPORT', 'AU_AUDITOR'),
    readEmails: has('AU_OPS_ADMIN', 'AU_OPS_VIEWER', 'AU_SUPPORT'),
    readAudit: has('AU_OPS_ADMIN', 'AU_AUDITOR'),
    resend: has('AU_OPS_ADMIN', 'AU_SUPPORT'),
    admin: has('AU_OPS_ADMIN'),
  };
}
