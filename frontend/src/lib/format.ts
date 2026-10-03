export function formatINR(paise: number): string {
  const rupees = Math.floor(paise / 100);
  const p = paise % 100;
  return `₹${rupees.toLocaleString('en-IN')}.${String(p).padStart(2, '0')}`;
}

/** API failure; `field` names the offending input when the server tied the error to one. */
export class ApiError extends Error {
  readonly status: number;
  readonly field?: string;

  constructor(message: string, status: number, field?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.field = field;
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const { headers, ...rest } = init || {};
  const res = await fetch(path, {
    credentials: 'include',
    ...rest,
    headers: { 'Content-Type': 'application/json', ...csrfHeaders(), ...(headers || {}) },
  });
  const data = (await res.json().catch(() => ({}))) as { error?: string; field?: string };
  if (!res.ok) {
    throw new ApiError(data.error || `Request failed (${res.status})`, res.status, data.field);
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
