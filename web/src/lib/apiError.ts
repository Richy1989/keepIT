/**
 * Pulls a human-readable message out of an API error body — either a `{ error }` shape or an
 * ASP.NET (Validation)ProblemDetails. ValidationProblemDetails carries the real messages in
 * `errors` (keyed by field / Identity code); surface those over the generic title.
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  if (error && typeof error === 'object') {
    const e = error as {
      error?: string;
      detail?: string;
      title?: string;
      errors?: Record<string, string[]>;
    };
    if (e.errors && typeof e.errors === 'object') {
      const messages = Object.values(e.errors).flat().filter(Boolean);
      if (messages.length > 0) return messages.join(' ');
    }
    return e.error ?? e.detail ?? e.title ?? fallback;
  }
  return fallback;
}

/**
 * Like {@link apiErrorMessage}, but reads the HTTP status first.
 *
 * Some failures carry no body at all — the rate limiter returns 429 with zero bytes — so a
 * body-only reading falls through to the caller's fallback. On a sign-in form that means telling
 * someone their *correct* password is wrong while they're merely being throttled.
 */
export function apiErrorMessageFor(
  response: Response | undefined,
  error: unknown,
  fallback: string,
): string {
  const status = response?.status;
  if (status === 429) {
    const retryAfter = Number(response?.headers.get('Retry-After'));
    return Number.isFinite(retryAfter) && retryAfter > 0
      ? `Too many attempts. Try again in ${retryAfter} second${retryAfter === 1 ? '' : 's'}.`
      : 'Too many attempts. Try again in a minute.';
  }
  if (status !== undefined && status >= 500) {
    return 'The server is unavailable right now. Please try again in a moment.';
  }
  return apiErrorMessage(error, fallback);
}
