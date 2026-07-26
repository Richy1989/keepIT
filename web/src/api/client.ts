import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from './schema';
import type { AuthResponseDto } from './types';
import { tokenStore } from '../auth/tokenStore';

/**
 * The typed API client (openapi-fetch over the generated `paths`). One instance for the whole app.
 *
 * Auth is handled by middleware:
 *  - `onRequest` attaches `Authorization: Bearer <token>`, proactively refreshing an expired token
 *    first (single-flight) so requests rarely race the 15-minute access-token expiry.
 *  - `onResponse` retries a 401 on a protected route once behind a fresh token, and only reports the
 *    session lost when the refresh itself came back 401.
 *
 * `credentials: 'include'` ensures the httpOnly refresh cookie rides along to /api/auth/*.
 */

/**
 * Routes that must never carry a bearer token or trigger a refresh. `login`/`register`/`refresh`
 * establish the session; `logout`/`forgot-password`/`reset-password` work without one, and
 * refreshing before `logout` in particular would rotate the very cookie we're about to revoke.
 */
const AUTH_FREE = new Set([
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
  '/api/auth/forgot-password',
  '/api/auth/reset-password',
]);

/** Event dispatched when the session is irrecoverably gone; AuthContext listens and signs out. */
export const UNAUTHORIZED_EVENT = 'keepit:unauthorized';

/**
 * Outcome of a refresh attempt. The distinction matters and must survive all the way to the caller:
 * only `'unauthorized'` (the cookie is missing/expired/revoked) may sign the user out. A transient
 * failure leaves a perfectly valid cookie in the jar, and treating it as a lost session would evict
 * someone mid-edit over a rate-limit blip. See ARCHITECTURE.md "Auth flow".
 */
export type RefreshResult = 'ok' | 'unauthorized' | 'transient';

let refreshing: Promise<RefreshResult> | null = null;

/** How long a single refresh round trip may hang before we call it transient and move on. */
const REFRESH_TIMEOUT_MS = 10_000;

/** One attempt against /api/auth/refresh. */
async function refreshOnce(): Promise<RefreshResult> {
  try {
    // Bounded: this runs while holding the cross-tab Web Lock, so a hang here would stall every
    // other tab's requests too.
    const res = await fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'include',
      signal: AbortSignal.timeout(REFRESH_TIMEOUT_MS),
    });
    if (!res.ok) return res.status === 401 ? 'unauthorized' : 'transient';

    // A 2xx with an unusable body is a transient server fault, not a fresh session. Storing it
    // would poison the store with an undefined token and a NaN expiry, and every later request
    // would 401 forever while `isExpiringSoon` cheerfully reported the token healthy.
    const data = (await res.json()) as AuthResponseDto;
    if (!data?.accessToken || !data.accessTokenExpiresAtUtc) return 'transient';
    return tokenStore.set(data.accessToken, data.accessTokenExpiresAtUtc) ? 'ok' : 'transient';
  } catch {
    return 'transient';
  }
}

/** Refreshes the access token, retrying once on a transient failure before giving up. */
async function doRefresh(): Promise<RefreshResult> {
  let result = await refreshOnce();
  if (result === 'transient') {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    result = await refreshOnce();
  }
  if (result === 'unauthorized') tokenStore.clear();
  return result;
}

/**
 * Single-flight wrapper: concurrent callers in this tab share one in-flight refresh, and a
 * cross-tab Web Lock serializes refreshes between tabs — they share one refresh cookie, and two
 * concurrent rotations of the same cookie would trip the server's replay detection.
 */
export function refreshAccessToken(): Promise<RefreshResult> {
  // The async wrapper also flattens the lock manager's Promise<Promise<…>> (older lib.dom typings
  // don't unwrap the callback's promise).
  refreshing ??= (async () => {
    if (typeof navigator !== 'undefined' && navigator.locks) {
      return navigator.locks.request('keepit:refresh', doRefresh);
    }
    return doRefresh();
  })().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

/**
 * Untouched copy of each in-flight request, kept so a 401 can be replayed behind a fresh token.
 * The clone has to be taken *before* dispatch: `onResponse` is handed the very same Request object
 * that `fetch` consumed, and cloning a disturbed body throws.
 */
const replayable = new WeakMap<Request, Request>();

const authMiddleware: Middleware = {
  async onRequest({ request, schemaPath }) {
    if (AUTH_FREE.has(schemaPath)) return request;
    if (tokenStore.isExpiringSoon()) await refreshAccessToken();
    if (tokenStore.token) {
      request.headers.set('Authorization', `Bearer ${tokenStore.token}`);
      replayable.set(request, request.clone());
    }
    return request;
  },
  async onResponse({ request, response, schemaPath }) {
    if (response.status !== 401 || AUTH_FREE.has(schemaPath)) return response;

    const result = await refreshAccessToken();
    if (result === 'unauthorized') {
      tokenStore.clear();
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
      return response;
    }
    // Transient: keep the session, but surface the 401 — replaying with the same stale token would
    // only 401 again.
    if (result === 'transient' || !tokenStore.token) return response;

    // Refreshed: replay once behind the new token so the caller never sees the blip. This is the
    // "silent refresh on 401" the architecture promises; without it every mutation that races the
    // token expiry fails outright, since TanStack's `retry` covers queries only. The replay goes
    // through the raw `fetch`, so it cannot re-enter this middleware and cannot loop.
    const replay = replayable.get(request);
    if (!replay) return response;
    replayable.delete(request);
    replay.headers.set('Authorization', `Bearer ${tokenStore.token}`);
    return fetch(replay);
  },
};

export const api = createClient<paths>({ credentials: 'include' });
api.use(authMiddleware);
