import { useState, type FormEvent } from 'react';
import { useDeleteAccount } from './queries';
import { useAuth } from '../../auth/AuthContext';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { apiErrorMessage } from '../../lib/apiError';

/**
 * Deletes the account, in two steps: the password, then a confirmation that says it can't be
 * undone. Asking for the password again means a computer left signed in can't be used to erase
 * someone's notes. Once the server has deleted the account, this device signs out, which lands on
 * the sign-in page.
 */
export function DeleteAccountForm() {
  const { logout } = useAuth();
  const remove = useDeleteAccount();
  const [password, setPassword] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!password) {
      setError('Enter your password to delete your account.');
      return;
    }
    setConfirming(true);
  }

  async function onConfirm() {
    try {
      await remove.mutateAsync(password);
      setConfirming(false);
      await logout();
    } catch (err) {
      setConfirming(false);
      setError(apiErrorMessage(err, 'Could not delete the account.'));
    }
  }

  return (
    <form onSubmit={onSubmit} className="max-w-md space-y-3">
      <ul className="list-disc space-y-1 pl-5 text-sm text-text-muted">
        <li>Your notes, with every photo and recording in them, and your lists.</li>
        <li>Your profile picture, settings and notifications.</li>
        <li>
          Notes others shared with you stay with their owners; notes you shared are gone for everyone.
        </li>
      </ul>
      <p className="text-sm text-text-muted">
        Want a copy first? Export your notes under <span className="font-medium text-text">Your data</span>.
      </p>

      <label className="block">
        <span className="mb-1 block text-xs font-medium text-text-muted">Password</span>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          className="focus-ring w-full max-w-sm rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-text placeholder:text-text-faint"
        />
      </label>

      {error && <p className="rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">{error}</p>}

      <button
        type="submit"
        disabled={remove.isPending}
        className="focus-ring mt-1 rounded-lg bg-danger-bg px-4 py-2 text-sm font-semibold text-danger transition hover:brightness-110 disabled:opacity-60"
      >
        Delete account…
      </button>

      {confirming && (
        <ConfirmDialog
          title="Delete your account?"
          body="Your account and everything in it are deleted from this server for good. This can't be undone."
          confirmLabel="Delete my account"
          tone="danger"
          busy={remove.isPending}
          onCancel={() => setConfirming(false)}
          onConfirm={() => void onConfirm()}
        />
      )}
    </form>
  );
}
