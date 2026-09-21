namespace keepITCore.Infrastructure.Email;

/// <summary>
/// SMTP settings bound from the <c>Email</c> config section. Email is <b>optional</b>: when
/// <see cref="SmtpHost"/> is empty the app falls back to <see cref="LogOnlyEmailSender"/>, so a
/// personal instance needs no mail server at all (the reset link lands in the server log instead).
/// </summary>
public class EmailOptions
{
    public const string SectionName = "Email";

    /// <summary>SMTP server hostname. Empty/absent = email delivery disabled (log fallback).</summary>
    public string? SmtpHost { get; set; }

    /// <summary>SMTP port. 587 (submission + STARTTLS) is the usual choice; 465 for implicit TLS.</summary>
    public int SmtpPort { get; set; } = 587;

    /// <summary>SMTP username. Leave empty for an unauthenticated relay.</summary>
    public string? SmtpUsername { get; set; }

    /// <summary>SMTP password. Supply via environment (<c>Email__SmtpPassword</c>), not the JSON file.</summary>
    public string? SmtpPassword { get; set; }

    /// <summary>From address, e.g. <c>keepIT &lt;no-reply@example.com&gt;</c>. Required when SMTP is configured.</summary>
    public string? From { get; set; }

    /// <summary>
    /// True (default) = STARTTLS, which the server must offer or nothing is sent (see
    /// <see cref="AllowUnencrypted"/>); false = implicit TLS (use with port 465).
    /// </summary>
    public bool UseStartTls { get; set; } = true;

    /// <summary>
    /// Lets STARTTLS mail go out unencrypted when the server doesn't offer it. Off by default,
    /// because a missing offer is exactly what someone on the network path produces by stripping
    /// it, and the conversation then carries reset links and the SMTP password in plain text. Only
    /// for a trusted relay on your own network.
    /// </summary>
    public bool AllowUnencrypted { get; set; }

    /// <summary>Whether SMTP delivery is configured (a host is present).</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(SmtpHost);
}
