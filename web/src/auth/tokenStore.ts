/**
 * In-memory holder for the short-lived JWT access token.
 *
 * The access token is deliberately NOT persisted (no localStorage) — only kept in this module's
 * closure for the page's lifetime. The long-lived refresh token lives in an httpOnly cookie the
 * browser sends automatically; on reload we restore the session by calling /api/auth/refresh.
 * See ARCHITECTURE.md "Auth flow".
 */
let accessToken: string | null = null;
let expiresAtMs: number | null = null;

export const tokenStore = {
  /** The current access token, or null when signed out. */
  get token(): string | null {
    return accessToken;
  },

  /** Stores a freshly issued access token and its expiry (ISO-8601 UTC). */
  set(token: string, expiresAtUtc: string): void {
    accessToken = token;
    expiresAtMs = new Date(expiresAtUtc).getTime();
  },

  /** Drops the in-memory token (sign-out, or a failed refresh). */
  clear(): void {
    accessToken = null;
    expiresAtMs = null;
  },

  /**
   * True when there's no token, or it expires within `skewMs`. The request middleware uses this to
   * refresh proactively so a request rarely races the token's expiry.
   */
  isExpiringSoon(skewMs = 30_000): boolean {
    return expiresAtMs === null || Date.now() > expiresAtMs - skewMs;
  },
};
