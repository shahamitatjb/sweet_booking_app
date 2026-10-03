export function formatINR(paise: number): string {
  const rupees = Math.floor(paise / 100);
  const p = paise % 100;
  return `₹${rupees.toLocaleString('en-IN')}.${String(p).padStart(2, '0')}`;
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const { headers, ...rest } = init || {};
  const res = await fetch(path, {
    credentials: 'include',
    ...rest,
    headers: { 'Content-Type': 'application/json', ...csrfHeaders(), ...(headers || {}) },
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error((data as { error?: string })?.error || `Request failed (${res.status})`);
  }
  return data as T;
}

/**
 * Double-submit CSRF: the API sets a readable XSRF-TOKEN cookie; every state-changing
 * request must echo it back in X-XSRF-TOKEN or Spring Security rejects it (403).
 */
export function csrfHeaders(): Record<string, string> {
  if (typeof document === 'undefined') return {};
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? { 'X-XSRF-TOKEN': decodeURIComponent(m[1]) } : {};
}
