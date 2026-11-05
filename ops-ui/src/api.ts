// All calls go to the gateway on this origin: the session cookie identifies the person, the gateway adds their
// access token, and each service decides what they may do.

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

function csrfToken(): string | undefined {
  return document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.substring('XSRF-TOKEN='.length);
}

async function call<T>(method: 'GET' | 'POST', url: string): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = csrfToken();
  if (method === 'POST' && token) {
    headers['X-XSRF-TOKEN'] = decodeURIComponent(token);
  }
  const response = await fetch(url, { method, headers, credentials: 'same-origin' });
  if (response.status === 401) {
    // The session ended: sign in again and come back here
    window.location.href = '/oauth2/authorization/login';
    throw new ApiError(401, 'Signing in again');
  }
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (!response.ok && response.status !== 409) {
    const message = body?.message ?? body?.error ?? `${response.status} ${response.statusText}`;
    throw new ApiError(response.status, response.status === 403 ? 'You are not allowed to do this' : message);
  }
  return body as T;
}

export const get = <T,>(url: string) => call<T>('GET', url);
export const post = <T,>(url: string) => call<T>('POST', url);

/**
 * A real form POST, not fetch: the gateway answers with a redirect to the identity provider's logout page, and only
 * a page navigation can follow it there and back. The CSRF token travels as the _csrf form field.
 */
export function signOut() {
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = '/logout';
  const token = csrfToken();
  if (token) {
    const field = document.createElement('input');
    field.type = 'hidden';
    field.name = '_csrf';
    field.value = decodeURIComponent(token);
    form.appendChild(field);
  }
  document.body.appendChild(form);
  form.submit();
}

/** Query string from the filled-in filters only. */
export function query(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  });
  const s = q.toString();
  return s ? `?${s}` : '';
}
