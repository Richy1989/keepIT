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
