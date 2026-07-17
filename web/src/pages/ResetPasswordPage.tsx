import { useState, type FormEvent, type InputHTMLAttributes } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { apiErrorMessage } from '../lib/apiError';
import { TypewriterIcon } from '../components/icons';

/**
 * Landing page for the emailed password-reset link (`/reset-password?email=…&token=…`). Posts the
 * token + new password to the API; a successful reset signs the user out everywhere, so on success
 * we only offer the way back to sign-in. Reachable authed or not — the link decides, not the
 * session.
 */
export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const email = params.get('email') ?? '';
  const token = params.get('token') ?? '';

  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const linkValid = email !== '' && token !== '';

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (password !== confirm) {
      setError('The passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      const { error: apiError, response } = await api.POST('/api/auth/reset-password', {
        body: { email, token, newPassword: password },
      });
      if (apiError || !response.ok)
        throw new Error(apiErrorMessage(apiError, 'Could not reset the password.'));
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-full place-items-center bg-canvas px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex items-center justify-center gap-2.5">
          <span className="grid size-10 place-items-center rounded-xl bg-accent/15 text-accent">
            <TypewriterIcon className="text-2xl" />
          </span>
          <span className="text-2xl font-semibold tracking-tight">keepIT</span>
        </div>

        <div className="rounded-2xl border border-border-subtle bg-surface p-6 shadow-xl shadow-black/40">
          <h1 className="text-lg font-medium">Choose a new password</h1>

          {!linkValid ? (
            <p className="mt-4 rounded-lg bg-rose-500/10 px-3 py-2 text-sm text-rose-300">
              This reset link is incomplete. Open the link from the email again, or request a new
              one from the sign-in page.
            </p>
          ) : done ? (
            <div className="mt-4 space-y-4">
              <p className="rounded-lg bg-accent/10 px-3 py-2 text-sm text-accent">
                Password changed. You've been signed out on all devices — sign in with your new
                password.
              </p>
              <Link
                to="/login"
                className="focus-ring block w-full rounded-lg bg-accent px-4 py-2.5 text-center text-sm font-semibold text-black transition hover:bg-accent-strong"
              >
                Go to sign in
              </Link>
            </div>
          ) : (
            <>
              <p className="mt-1 text-sm text-text-muted">
                Resetting the password for <span className="font-medium">{email}</span>.
              </p>
              <form onSubmit={onSubmit} className="mt-6 space-y-3">
                <Field
                  label="New password"
                  type="password"
                  value={password}
                  onChange={setPassword}
                  placeholder="At least 8 characters"
                  autoComplete="new-password"
                  required
                />
                <Field
                  label="Confirm new password"
                  type="password"
                  value={confirm}
                  onChange={setConfirm}
                  placeholder="••••••••"
                  autoComplete="new-password"
                  required
                />

                {error && (
                  <p className="rounded-lg bg-rose-500/10 px-3 py-2 text-sm text-rose-300">
                    {error}
                  </p>
                )}

                <button
                  type="submit"
                  disabled={busy}
                  className="focus-ring mt-2 w-full rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-60"
                >
                  {busy ? 'Please wait…' : 'Set new password'}
                </button>
              </form>
            </>
          )}
        </div>

        <p className="mt-5 text-center text-sm text-text-muted">
          <Link to="/login" className="font-medium text-accent hover:underline">
            Back to sign in
          </Link>
        </p>
      </div>
    </div>
  );
}

/** A labelled text input, matching the auth form styling. */
function Field({
  label,
  value,
  onChange,
  ...rest
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
} & Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'>) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-text-muted">{label}</span>
      <input
        {...rest}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="focus-ring w-full rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-text placeholder:text-text-faint"
      />
    </label>
  );
}
