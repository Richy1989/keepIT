namespace keepITCore.Settings.Dtos
{
    /// <summary>
    /// How this server delivers email (<c>GET api/settings/email-status</c>), so the Settings page
    /// can say when password-reset emails are switched off by missing configuration, rather than
    /// users simply never receiving them.
    /// </summary>
    public class EmailStatusDto
    {
        /// <summary>True when email goes out via SMTP; false when messages land in the server log.</summary>
        public bool SmtpConfigured { get; set; }

        /// <summary>
        /// The configured public address (<c>App:PublicBaseUrl</c>) that emailed links point at, or
        /// null when it isn't set.
        /// </summary>
        public string? PublicBaseUrl { get; set; }

        /// <summary>
        /// True when SMTP is configured but <see cref="PublicBaseUrl"/> is not: password-reset emails
        /// are then withheld, since a link can't be built from the incoming request safely.
        /// </summary>
        public bool ResetEmailsDisabled { get; set; }
    }
}
