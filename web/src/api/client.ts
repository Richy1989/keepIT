import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from './schema';
import { tokenStore } from '../auth/tokenStore';

/**
 * The typed API client (openapi-fetch over the generated `paths`). One instance for the whole app.
 *
 * Auth is handled by middleware:
 *  - `onRequest` attaches `Authorization: Bearer <token>`, proactively refreshing an expired token
 *    first (single-flight) so requests rarely race the 15-minute access-token expiry.
 *  - `onResponse` treats a 401 on a protected route as a lost session: it tries one refresh and, if
 *    that fails, clears the token and fires `keepit:unauthorized` so the app can return to login.
 *
 * `credentials: 'include'` ensures the httpOnly refresh cookie rides along to /api/auth/*.
 */

/** Routes that must never carry a bearer token or trigger a refresh (they establish the session). */
const AUTH_FREE = new Set(['/api/auth/login', '/api/auth/register', '/api/auth/refresh']);

/** Event dispatched when the session is irrecoverably gone; AuthContext listens and signs out. */
export const UNAUTHORIZED_EVENT = 'keepit:unauthorized';

let refreshing: Promise<boolean> | null = null;

/**
 * One attempt against /api/auth/refresh. Distinguishes "the session is gone" (401 — the cookie is
 * missing/expired/revoked) from a transient failure (429/5xx/network) where the cookie is still
 * perfectly valid and signing the user out would be wrong.
 */
async function refreshOnce(): Promise<'ok' | 'unauthorized' | 'transient'> {
  try {
    const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' });
    if (res.ok) {
      const data = (await res.json()) as { accessToken: string; accessTokenExpiresAtUtc: string };
      tokenStore.set(data.accessToken, data.accessTokenExpiresAtUtc);
      return 'ok';
    }
    return res.status === 401 ? 'unauthorized' : 'transient';
  } catch {
    return 'transient';
  }
}

/** Refreshes the access token, retrying once on a transient failure before giving up. */
async function doRefresh(): Promise<boolean> {
  let result = await refreshOnce();
  if (result === 'transient') {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    result = await refreshOnce();
  }
  if (result === 'ok') return true;
  if (result === 'unauthorized') {
    tokenStore.clear();
    return false;
  }
  // Still transient: keep whatever session we have. Only report the session lost when there's no
  // usable access token left to keep working with.
  return tokenStore.token !== null && !tokenStore.isExpiringSoon(0);
}

/**
 * Single-flight wrapper: concurrent callers in this tab share one in-flight refresh, and a
 * cross-tab Web Lock serializes refreshes between tabs — they share one refresh cookie, and two
 * concurrent rotations of the same cookie would trip the server's replay detection.
 */
export function refreshAccessToken(): Promise<boolean> {
  refreshing ??= (
    typeof navigator !== 'undefined' && navigator.locks
      ? navigator.locks.request('keepit:refresh', doRefresh)
      : doRefresh()
  ).finally(() => {
    refreshing = null;
  });
  return refreshing;
}

const authMiddleware: Middleware = {
  async onRequest({ request, schemaPath }) {
    if (AUTH_FREE.has(schemaPath)) return request;
    if (tokenStore.isExpiringSoon()) await refreshAccessToken();
    if (tokenStore.token) request.headers.set('Authorization', `Bearer ${tokenStore.token}`);
    return request;
  },
  async onResponse({ response, schemaPath }) {
    if (response.status === 401 && !AUTH_FREE.has(schemaPath)) {
      const ok = await refreshAccessToken();
      if (!ok) {
        tokenStore.clear();
        window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
      }
    }
    return response;
  },
};

export const api = createClient<paths>({ credentials: 'include' });
api.use(authMiddleware);
