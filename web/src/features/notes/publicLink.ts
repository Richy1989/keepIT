/**
 * Pure helpers for public links (ARCHITECTURE.md "Public links"): building the link, reading the
 * token back out of it, and the expiry choices the owner picks from. No React, so the unit tests
 * run them in node.
 *
 * The token lives in the URL **fragment** (`/p#<token>`): a browser never sends the fragment to a
 * server or puts it in a referer, so the token never reaches nginx's log or the API's.
 */

/** The page a public link opens. */
export const PUBLIC_PATH = '/p';

/**
 * The link to hand out. The server's own `url` wins: it is built on the operator's configured
 * public address (`App:PublicBaseUrl`). Without one, the address this app is open at is the best
 * there is.
 */
export function publicLinkUrl(origin: string, token: string, serverUrl?: string | null): string {
  if (serverUrl) return serverUrl;
  return `${origin.replace(/\/+$/, '')}${PUBLIC_PATH}#${token}`;
}

/** The token in a link's fragment (`#abc` → `abc`), or null when there is none or it can't be one. */
export function tokenFromHash(hash: string): string | null {
  const token = hash.replace(/^#/, '').trim();
  // A token is 43 base64url characters; anything else (a stray anchor, a mangled paste) opens nothing.
  return /^[A-Za-z0-9_-]{16,128}$/.test(token) ? token : null;
}

/** How long a link stays up: a fixed span, never, or a date the owner picks. */
export type ExpiryPreset = 'never' | '1d' | '7d' | '30d' | 'custom';

export const EXPIRY_PRESETS: { value: ExpiryPreset; label: string }[] = [
  { value: 'never', label: 'Never' },
  { value: '1d', label: '1 day' },
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
  { value: 'custom', label: 'Pick a date' },
];

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The expiry to send for a choice, as an ISO instant, or null for never. A custom date (`YYYY-MM-DD`
 * from a date input) means "through that day": the link stops at the end of it, in the owner's own
 * time zone. Returns undefined when a custom date is missing or not in the future.
 */
export function expiryFromPreset(preset: ExpiryPreset, now: Date, customDate?: string): string | null | undefined {
  switch (preset) {
    case 'never':
      return null;
    case '1d':
      return new Date(now.getTime() + DAY_MS).toISOString();
    case '7d':
      return new Date(now.getTime() + 7 * DAY_MS).toISOString();
    case '30d':
      return new Date(now.getTime() + 30 * DAY_MS).toISOString();
    case 'custom': {
      const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(customDate ?? '');
      if (!m) return undefined;
      const endOfDay = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 23, 59, 59);
      return endOfDay.getTime() > now.getTime() ? endOfDay.toISOString() : undefined;
    }
  }
}
