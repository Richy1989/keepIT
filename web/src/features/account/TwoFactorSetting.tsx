import { useState, type FormEvent, type InputHTMLAttributes } from 'react';
import {
  useDisableTwoFactor,
  useEnableTwoFactor,
  useNewRecoveryCodes,
  useStartTwoFactorSetup,
  useTwoFactorStatus,
} from './queries';
import { QrCode } from '../../components/QrCode';
import type { TwoFactorSetupDto } from '../../api/types';

/** Where the card is: showing the status, or one of the steps that change it. */
type Stage =
  | { kind: 'status' }
  | { kind: 'scan'; setup: TwoFactorSetupDto }
  | { kind: 'codes'; codes: string[] }
  | { kind: 'confirm'; action: 'disable' | 'renew' };

/**
 * Two-factor authentication with an authenticator app, in the Security section. Off, it asks for
 * the password and then shows a QR code to scan (or the key to type), and turns on once a code from
 * the app comes back right. The recovery codes are shown once, at that moment and whenever new ones
 * are made. On, it says how many are left and offers new ones or turning it off — both asking for
 * the password and a code, as the server does.
 */
export function TwoFactorSetting() {
  const status = useTwoFactorStatus();
  const [stage, setStage] = useState<Stage>({ kind: 'status' });
  const toStatus = () => setStage({ kind: 'status' });

  if (stage.kind === 'scan')
    return <ScanStep setup={stage.setup} onEnabled={(codes) => setStage({ kind: 'codes', codes })} onCancel={toStatus} />;
  if (stage.kind === 'codes') return <RecoveryCodes codes={stage.codes} onDone={toStatus} />;
  if (stage.kind === 'confirm')
    return (
      <ConfirmBothFactors
        action={stage.action}
        onDone={(codes) => (codes ? setStage({ kind: 'codes', codes }) : toStatus())}
        onCancel={toStatus}
      />
    );

  if (status.isPending) return <p className="text-sm text-text-muted">Loading…</p>;
  if (status.isError || !status.data)
    return <p className="text-sm text-danger">Couldn't load the two-factor status. Reload the page to try again.</p>;

  if (!status.data.enabled) return <StartSetup onStarted={(setup) => setStage({ kind: 'scan', setup })} />;

  const left = status.data.recoveryCodesLeft;
  return (
    <div className="max-w-md space-y-3">
      <p className="text-sm text-text">
        <span className="font-medium text-accent-ink">On.</span> Signing in asks for a code from your
        authenticator app as well as your password.
      </p>
      <p className={left <= 2 ? 'text-sm text-danger' : 'text-sm text-text-muted'}>
        {left === 0
          ? 'You have no recovery codes left. Make new ones, or losing your phone locks you out.'
          : `${left} recovery code${left === 1 ? '' : 's'} left.${left <= 2 ? ' Make new ones soon.' : ''}`}
      </p>
      <div className="flex flex-wrap gap-2 pt-1">
        <SecondaryButton onClick={() => setStage({ kind: 'confirm', action: 'renew' })}>New recovery codes</SecondaryButton>
        <button
          type="button"
          onClick={() => setStage({ kind: 'confirm', action: 'disable' })}
          className="focus-ring rounded-lg bg-danger-bg px-4 py-2 text-sm font-semibold text-danger transition hover:brightness-110"
        >
          Turn off…
        </button>
      </div>
    </div>
  );
}

/** Off: what it does, and the password to start setting it up. */
function StartSetup({ onStarted }: { onStarted: (setup: TwoFactorSetupDto) => void }) {
  const start = useStartTwoFactorSetup();
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      onStarted(await start.mutateAsync(password));
    } catch (err) {
      setError(messageOf(err, 'Could not start the setup.'));
    }
  }

  return (
    <form onSubmit={onSubmit} className="max-w-md space-y-3">
      <p className="text-sm text-text-muted">
        Off. Turn it on to ask for a code from an authenticator app, such as Aegis, 2FAS or Google
        Authenticator, each time you sign in. Someone who learns your password still can't get in
        without your phone.
      </p>
      <Field label="Password" value={password} onChange={setPassword} type="password" autoComplete="current-password" required />
      {error && <ErrorText>{error}</ErrorText>}
      <PrimaryButton busy={start.isPending}>Set up…</PrimaryButton>
    </form>
  );
}

/** The QR code (and the key, for typing in), then the code that proves the app has it. */
function ScanStep({
  setup,
  onEnabled,
  onCancel,
}: {
  setup: TwoFactorSetupDto;
  onEnabled: (codes: string[]) => void;
  onCancel: () => void;
}) {
  const enable = useEnableTwoFactor();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      onEnabled(await enable.mutateAsync(code));
    } catch (err) {
      setError(messageOf(err, 'Could not turn on two-factor authentication.'));
    }
  }

  return (
    <form onSubmit={onSubmit} className="max-w-md space-y-4">
      <p className="text-sm text-text-muted">
        1. In your authenticator app, add an account and scan this code.
      </p>
      <QrCode rows={setup.qrCode} label="QR code to add keepIT to your authenticator app" className="size-48" />
      <div className="text-sm text-text-muted">
        Can't scan it? Enter this key instead:
        <code className="mt-1 block select-all rounded-lg bg-canvas px-3 py-2 font-mono text-sm tracking-wider text-text">
          {setup.sharedKey}
        </code>
      </div>
      <p className="text-sm text-text-muted">2. Enter the six-digit code the app now shows for keepIT.</p>
      <Field
        label="Code"
        value={code}
        onChange={setCode}
        placeholder="123 456"
        inputMode="numeric"
        autoComplete="one-time-code"
        required
      />
      {error && <ErrorText>{error}</ErrorText>}
      <div className="flex flex-wrap gap-2">
        <PrimaryButton busy={enable.isPending}>Turn on</PrimaryButton>
        <SecondaryButton onClick={onCancel}>Cancel</SecondaryButton>
      </div>
    </form>
  );
}

/** A new set of recovery codes, shown this once: copy, download, and a reminder of what they're for. */
function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  const text = `keepIT recovery codes\nEach one signs you in once in place of a code from your authenticator app.\n\n${codes.join('\n')}\n`;
  // The clipboard is only there on https (and localhost); a server on plain http gets the download alone.
  const canCopy = typeof navigator !== 'undefined' && !!navigator.clipboard;

  function download() {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'keepit-recovery-codes.txt';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }

  return (
    <div className="max-w-md space-y-4">
      <p className="text-sm text-text">
        <span className="font-medium text-accent-ink">Two-factor authentication is on.</span> Save these
        recovery codes somewhere safe, away from your phone. If you lose it, each code signs you in once.
        They won't be shown again.
      </p>
      <ul className="grid grid-cols-2 gap-x-6 gap-y-1 rounded-lg bg-canvas px-4 py-3 font-mono text-sm tracking-wider text-text">
        {codes.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
      <div className="flex flex-wrap gap-2">
        <SecondaryButton onClick={download}>Download</SecondaryButton>
        {canCopy && (
          <SecondaryButton
            onClick={() => {
              void navigator.clipboard.writeText(codes.join('\n')).then(() => setCopied(true));
            }}
          >
            {copied ? 'Copied' : 'Copy'}
          </SecondaryButton>
        )}
        <button
          type="button"
          onClick={onDone}
          className="focus-ring rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-black transition hover:bg-accent-strong"
        >
          I've saved them
        </button>
      </div>
    </div>
  );
}

/**
 * Turning two-factor off, or making new recovery codes: both take the password and a code, so a
 * computer left signed in can do neither. `onDone` gets the new codes, or nothing when it was off.
 */
function ConfirmBothFactors({
  action,
  onDone,
  onCancel,
}: {
  action: 'disable' | 'renew';
  onDone: (codes?: string[]) => void;
  onCancel: () => void;
}) {
  const disable = useDisableTwoFactor();
  const renew = useNewRecoveryCodes();
  const busy = disable.isPending || renew.isPending;
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      if (action === 'disable') {
        await disable.mutateAsync({ password, code });
        onDone();
      } else {
        onDone(await renew.mutateAsync({ password, code }));
      }
    } catch (err) {
      setError(messageOf(err, "That didn't work. Check the password and the code."));
    }
  }

  return (
    <form onSubmit={onSubmit} className="max-w-md space-y-3">
      <p className="text-sm text-text-muted">
        {action === 'disable'
          ? 'Signing in will ask for your password only. Your authenticator entry and recovery codes stop working.'
          : 'Your current recovery codes stop working, and you get ten new ones.'}
      </p>
      <Field label="Password" value={password} onChange={setPassword} type="password" autoComplete="current-password" required />
      <Field
        label="Code from your app, or a recovery code"
        value={code}
        onChange={setCode}
        autoComplete="one-time-code"
        autoCapitalize="none"
        spellCheck={false}
        required
      />
      {error && <ErrorText>{error}</ErrorText>}
      <div className="flex flex-wrap gap-2">
        {action === 'disable' ? (
          <button
            type="submit"
            disabled={busy}
            aria-busy={busy}
            className="focus-ring rounded-lg bg-danger-bg px-4 py-2 text-sm font-semibold text-danger transition hover:brightness-110 disabled:opacity-60"
          >
            Turn off
          </button>
        ) : (
          <PrimaryButton busy={busy}>Make new codes</PrimaryButton>
        )}
        <SecondaryButton onClick={onCancel}>Cancel</SecondaryButton>
      </div>
    </form>
  );
}

/** The message of a failed two-factor call; the hooks make it ready to show. */
function messageOf(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/** A labelled input, styled like the other Security forms. */
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
        className="focus-ring w-full max-w-sm rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-text placeholder:text-text-faint"
      />
    </label>
  );
}

function ErrorText({ children }: { children: string }) {
  return (
    <p role="alert" className="rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">
      {children}
    </p>
  );
}

function PrimaryButton({ busy, children }: { busy: boolean; children: string }) {
  return (
    <button
      type="submit"
      disabled={busy}
      aria-busy={busy}
      className="focus-ring rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-60"
    >
      {busy ? 'Please wait…' : children}
    </button>
  );
}

function SecondaryButton({ onClick, children }: { onClick: () => void; children: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="focus-ring rounded-lg bg-surface px-4 py-2 text-sm text-text-muted ring-1 ring-border-strong transition hover:bg-surface-hover hover:text-text"
    >
      {children}
    </button>
  );
}
