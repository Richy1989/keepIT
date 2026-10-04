import { useState, type FormEvent } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { apiErrorMessage } from '../../lib/apiError';
import { useUpdateDisplayName } from './queries';

/** Matches the backend's limit on `UpdateProfileRequestDto.DisplayName`. */
const MAX_LENGTH = 100;

/**
 * Display-name control: edit the name keepIT shows for the account, or clear it to fall back to
 * the email. Until the field is touched it shows the current name — including one just changed on
 * another device, which arrives through the realtime `account` push.
 */
export function DisplayNameForm() {
  const { user } = useAuth();
  const update = useUpdateDisplayName();
  // null while untouched, so the field follows the server's name rather than a stale copy of it.
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const current = user?.displayName ?? '';
  const value = draft ?? current;
  const changed = value.trim() !== current;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!changed) return;
    setError(null);
    try {
      await update.mutateAsync(value);
      setDraft(null);
      setSaved(true);
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not save the display name.'));
    }
  }

  return (
    <form onSubmit={onSubmit} className="max-w-sm">
      <label className="block">
        <span className="mb-1 block text-xs font-medium text-text-muted">Display name</span>
        <input
          type="text"
          value={value}
          onChange={(e) => {
            setDraft(e.target.value);
            setSaved(false);
          }}
          maxLength={MAX_LENGTH}
          autoComplete="name"
          placeholder={user?.email ?? 'Your name'}
          className="focus-ring w-full rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-text placeholder:text-text-faint"
        />
      </label>
      <p className="mt-1 text-xs text-text-faint">
        Shown in your account menu. Leave it empty to use your email.
      </p>

      {error && (
        <p className="mt-3 rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">{error}</p>
      )}

      <div className="mt-3 flex items-center gap-3">
        <button
          type="submit"
          disabled={!changed || update.isPending}
          className="focus-ring rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-60"
        >
          {update.isPending ? 'Saving…' : 'Save name'}
        </button>
        {saved && !changed && (
          <span role="status" className="text-sm text-accent-ink">
            Saved
          </span>
        )}
      </div>
    </form>
  );
}
