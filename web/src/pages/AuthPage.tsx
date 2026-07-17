import { useState, type FormEvent, type InputHTMLAttributes } from 'react';
import { useAuth } from '../auth/AuthContext';
import { api } from '../api/client';
import { apiErrorMessage } from '../lib/apiError';
import { TypewriterIcon } from '../components/icons';

type Mode = 'login' | 'register' | 'forgot';

/** Sign-in / sign-up / forgot-password screen. Toggles between the modes; login and register
 * submit via the auth context, forgot posts directly (no session involved). */
export function AuthPage() {
  const { login, register } = useAuth();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Forgot mode: true once the request went through — the form is replaced by the confirmation.
  const [resetRequested, setResetRequested] = useState(false);

  function switchMode(next: Mode) {
    setMode(next);
    setError(null);
    setResetRequested(false);
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (mode === 'login') await login(email, password);
      else if (mode === 'register') await register(email, password, displayName);
      else {
        const { error: apiError, response } = await api.POST('/api/auth/forgot-password', {
          body: { email },
        });
        if (apiError || !response.ok)
          throw new Error(apiErrorMessage(apiError, 'Could not request a reset link.'));
        setResetRequested(true);
      }
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
          <h1 className="text-lg font-medium">
            {mode === 'login' && 'Welcome back'}
            {mode === 'register' && 'Create your account'}
            {mode === 'forgot' && 'Reset your password'}
          </h1>
          <p className="mt-1 text-sm text-text-muted">
            {mode === 'login' && 'Sign in to your notes.'}
            {mode === 'register' && 'Start capturing notes in seconds.'}
            {mode === 'forgot' && "Enter your account email and we'll send you a reset link."}
          </p>

          {mode === 'forgot' && resetRequested ? (
            <div className="mt-6 space-y-4">
              <p className="rounded-lg bg-accent/10 px-3 py-2 text-sm text-accent">
                If an account exists for <span className="font-medium">{email}</span>, a reset link
                is on its way. The link is valid for 2 hours.
              </p>
              <button
                type="button"
                onClick={() => switchMode('login')}
                className="focus-ring w-full rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-black transition hover:bg-accent-strong"
              >
                Back to sign in
              </button>
            </div>
          ) : (
            <form onSubmit={onSubmit} className="mt-6 space-y-3">
              {mode === 'register' && (
                <Field
                  label="Display name"
                  type="text"
                  value={displayName}
                  onChange={setDisplayName}
                  placeholder="Optional"
                  autoComplete="name"
                />
              )}
              <Field
                label="Email"
                type="email"
                value={email}
                onChange={setEmail}
                placeholder="you@example.com"
                autoComplete="email"
                required
              />
              {mode !== 'forgot' && (
                <Field
                  label="Password"
                  type="password"
                  value={password}
                  onChange={setPassword}
                  placeholder={mode === 'register' ? 'At least 8 characters' : '••••••••'}
                  autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                  required
                />
              )}
              {mode === 'login' && (
                <div className="text-right">
                  <button
                    type="button"
                    onClick={() => switchMode('forgot')}
                    className="text-xs font-medium text-text-muted hover:text-accent hover:underline"
                  >
                    Forgot password?
                  </button>
                </div>
              )}

              {error && (
                <p className="rounded-lg bg-rose-500/10 px-3 py-2 text-sm text-rose-300">{error}</p>
              )}

              <button
                type="submit"
                disabled={busy}
                className="focus-ring mt-2 w-full rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-60"
              >
                {busy
                  ? 'Please wait…'
                  : mode === 'login'
                    ? 'Sign in'
                    : mode === 'register'
                      ? 'Create account'
                      : 'Send reset link'}
              </button>
            </form>
          )}
        </div>

        <p className="mt-5 text-center text-sm text-text-muted">
          {mode === 'forgot' ? (
            <>
              Remembered it after all?{' '}
              <button
                type="button"
                onClick={() => switchMode('login')}
                className="font-medium text-accent hover:underline"
              >
                Sign in
              </button>
            </>
          ) : (
            <>
              {mode === 'login' ? "Don't have an account? " : 'Already have an account? '}
              <button
                type="button"
                onClick={() => switchMode(mode === 'login' ? 'register' : 'login')}
                className="font-medium text-accent hover:underline"
              >
                {mode === 'login' ? 'Sign up' : 'Sign in'}
              </button>
            </>
          )}
        </p>
      </div>
    </div>
  );
}

/** A labelled text input used by the auth form. */
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
