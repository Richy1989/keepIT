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

/** Calls /api/auth/refresh once (rotating the cookie) and stores the new access token. */
function doRefresh(): Promise<boolean> {
  return fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })
    .then(async (res) => {
      if (!res.ok) {
        tokenStore.clear();
        return false;
      }
      const data = (await res.json()) as { accessToken: string; accessTokenExpiresAtUtc: string };
      tokenStore.set(data.accessToken, data.accessTokenExpiresAtUtc);
      return true;
    })
    .catch(() => {
      tokenStore.clear();
      return false;
    });
}

/** Single-flight wrapper: concurrent callers share one in-flight refresh. */
export function refreshAccessToken(): Promise<boolean> {
  refreshing ??= doRefresh().finally(() => {
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
