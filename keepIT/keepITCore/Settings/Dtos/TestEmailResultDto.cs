namespace keepITCore.Settings.Dtos
{
    /// <summary>
    /// Result of the email-delivery test (<c>POST api/settings/test-email</c>). The test is a 200
    /// even when delivery fails — the outcome <em>is</em> the payload, so the UI can explain all
    /// three cases: delivered via SMTP, written to the server log (SMTP unconfigured), or failed.
    /// </summary>
    public class TestEmailResultDto
    {
        /// <summary>Whether SMTP is configured on the server (<c>Email:SmtpHost</c> is set). When false, the test message went to the server log instead of a mailbox.</summary>
        public bool SmtpConfigured { get; set; }

        /// <summary>True when the message was handed off without error (to the SMTP server, or to the log when unconfigured).</summary>
        public bool Sent { get; set; }

        /// <summary>The address the test message was sent to — always the caller's own account email.</summary>
        public string SentTo { get; set; } = string.Empty;

        /// <summary>The delivery error when <see cref="Sent"/> is false (e.g. connection refused, authentication failed), for the operator to act on.</summary>
        public string? Error { get; set; }
    }
}
