import { useAuth } from '../../auth/AuthContext';
import { AlertIcon } from '../../components/icons';
import { apiErrorMessage } from '../../lib/apiError';
import { useEmailStatus, useSendTestEmail } from './queries';

/**
 * "Send test email" control for the Settings page: posts to the test endpoint and explains the
 * outcome — delivered via SMTP, written to the server log (SMTP unconfigured), or failed with the
 * delivery error so the operator can fix the server's `Email__*` settings.
 *
 * Above it sits the server's email status. The one that matters: SMTP configured without
 * `App__PublicBaseUrl`, which leaves password-reset emails switched off. A test email still goes out
 * then, so without this notice "SMTP is working" would read as all being well.
 */
export function TestEmailSetting() {
  const { user } = useAuth();
  const send = useSendTestEmail();
  const result = send.data;
  const status = useEmailStatus().data;

  return (
    <div className="max-w-md space-y-3">
      {status?.resetEmailsDisabled && <ResetEmailsOffNotice />}

      {status?.smtpConfigured && status.publicBaseUrl && (
        <p className="text-sm text-text-muted">
          Password-reset links point to{' '}
          <span className="font-medium text-text">{status.publicBaseUrl}</span>.
        </p>
      )}

      <p className="text-sm text-text-muted">
        The test message is sent to your account address,{' '}
        <span className="font-medium text-text">{user?.email}</span>.
      </p>

      {result &&
        (!result.sent ? (
          <p className="rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">
            Delivery failed: {result.error ?? 'unknown error.'} Check the server's{' '}
            <code className="font-mono text-xs">Email__*</code> settings.
          </p>
        ) : result.smtpConfigured ? (
          <p className="rounded-lg bg-accent/10 px-3 py-2 text-sm text-accent">
            Test email sent to {result.sentTo} — check your inbox. SMTP is working.
          </p>
        ) : (
          <p className="rounded-lg bg-warning-bg px-3 py-2 text-sm text-warning">
            SMTP isn't configured on this server, so the test message was written to the server
            log instead. Password-reset links land there too; set{' '}
            <code className="font-mono text-xs">Email__SmtpHost</code> (and friends) to deliver
            real email.
          </p>
        ))}

      {send.isError && (
        <p className="rounded-lg bg-danger-bg px-3 py-2 text-sm text-danger">
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

/**
 * Why password-reset emails aren't going out, and the one setting that fixes it. Written for
 * whoever runs the server, since the reader may be any user of it; the address they are using
 * right now is offered because it is usually the right value.
 */
function ResetEmailsOffNotice() {
  return (
    <div role="status" className="flex gap-2.5 rounded-lg bg-warning-bg px-3 py-2.5 text-sm text-warning">
      <AlertIcon className="mt-0.5 shrink-0 text-base" />
      <div className="space-y-1.5">
        <p className="font-medium">Password-reset emails are switched off</p>
        <p>
          SMTP is set up, but this server's public address isn't. keepIT only builds reset links
          from that address, never from the incoming request, so it sends none until it's set.
          Whoever runs this server should set{' '}
          <code className="font-mono text-xs">App__PublicBaseUrl</code> to the address users open
          keepIT at, then restart it. On Unraid, that's the Public Base URL field.
        </p>
        <p>
          You're using <code className="font-mono text-xs">{window.location.origin}</code> right
          now, which is usually the right value.
        </p>
      </div>
    </div>
  );
}
