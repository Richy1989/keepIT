import { useState, type FormEvent, type InputHTMLAttributes } from 'react';
import { useChangePassword } from './queries';
import { apiErrorMessage } from '../../lib/apiError';

/** Change-password form (current / new / confirm) with inline validation and a success state. */
export function ChangePasswordForm() {
  const change = useChangePassword();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  function reset() {
    setCurrent('');
    setNext('');
    setConfirm('');
    setError(null);
    setDone(false);
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (next.length < 8) {
      setError('New password must be at least 8 characters.');
      return;
    }
    if (next !== confirm) {
      setError('New passwords do not match.');
      return;
    }
    try {
      await change.mutateAsync({ currentPassword: current, newPassword: next });
      setDone(true);
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not change the password.'));
    }
  }

  if (done) {
    return (
      <div className="rounded-xl border border-border-subtle bg-canvas/40 p-4">
        <p className="text-sm text-text-muted">
          Your password has been changed. For your security, your other devices have been signed out.
        </p>
        <button
          type="button"
          onClick={reset}
          className="focus-ring mt-4 rounded-lg bg-surface px-3 py-1.5 text-sm text-text-muted transition hover:bg-surface-hover hover:text-text"
        >
          Change again
        </button>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="max-w-sm space-y-3">
      <Field
        label="Current password"
        value={current}
        onChange={setCurrent}
        autoComplete="current-password"
        required
      />
      <Field
        label="New password"
        value={next}
        onChange={setNext}
        autoComplete="new-password"
        placeholder="At least 8 characters"
        required
      />
      <Field
        label="Confirm new password"
        value={confirm}
        onChange={setConfirm}
        autoComplete="new-password"
        required
      />

      {error && (
        <p className="rounded-lg bg-rose-500/10 px-3 py-2 text-sm text-rose-300">{error}</p>
      )}

      <button
        type="submit"
        disabled={change.isPending}
        className="focus-ring mt-1 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-60"
      >
        {change.isPending ? 'Saving…' : 'Update password'}
      </button>
    </form>
  );
}

/** A labelled password input. */
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
        type="password"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="focus-ring w-full rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-text placeholder:text-text-faint"
      />
    </label>
  );
}
