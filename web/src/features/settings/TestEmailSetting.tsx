import { useAuth } from '../../auth/AuthContext';
import { apiErrorMessage } from '../../lib/apiError';
import { useSendTestEmail } from './queries';

/**
 * "Send test email" control for the Settings page: posts to the test endpoint and explains the
 * outcome — delivered via SMTP, written to the server log (SMTP unconfigured), or failed with the
 * delivery error so the operator can fix the server's `Email__*` settings.
 */
export function TestEmailSetting() {
  const { user } = useAuth();
  const send = useSendTestEmail();
  const result = send.data;

  return (
    <div className="max-w-md space-y-3">
      <p className="text-sm text-text-muted">
        The test message is sent to your account address,{' '}
        <span className="font-medium text-text">{user?.email}</span>.
      </p>

      {result &&
        (!result.sent ? (
          <p className="rounded-lg bg-rose-500/10 px-3 py-2 text-sm text-rose-300">
            Delivery failed: {result.error ?? 'unknown error.'} Check the server's{' '}
            <code className="font-mono text-xs">Email__*</code> settings.
          </p>
        ) : result.smtpConfigured ? (
          <p className="rounded-lg bg-accent/10 px-3 py-2 text-sm text-accent">
            Test email sent to {result.sentTo} — check your inbox. SMTP is working.
          </p>
        ) : (
          <p className="rounded-lg bg-amber-500/10 px-3 py-2 text-sm text-amber-300">
            SMTP isn't configured on this server, so the test message was written to the server
            log instead. Password-reset links land there too; set{' '}
            <code className="font-mono text-xs">Email__SmtpHost</code> (and friends) to deliver
            real email.
          </p>
        ))}

      {send.isError && (
        <p className="rounded-lg bg-rose-500/10 px-3 py-2 text-sm text-rose-300">
          {apiErrorMessage(send.error, 'Could not run the email test.')}
        </p>
      )}

      <button
        type="button"
        onClick={() => send.mutate()}
        disabled={send.isPending}
        className="focus-ring mt-1 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-black transition hover:bg-accent-strong disabled:opacity-60"
      >
        {send.isPending ? 'Sending…' : 'Send test email'}
      </button>
    </div>
  );
}
