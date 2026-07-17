namespace keepITCore.Infrastructure.Email;

/// <summary>
/// Fallback when no SMTP host is configured: writes the full message to the server log at Warning
/// level (visible under the default log config, and hard to miss). On a self-hosted personal
/// instance this is a feature, not a stopgap — the operator owns the box, so "check the logs for
/// the reset link" is a legitimate delivery channel that needs zero mail configuration.
/// </summary>
public class LogOnlyEmailSender : IEmailSender
{
    private readonly ILogger<LogOnlyEmailSender> _logger;

    public LogOnlyEmailSender(ILogger<LogOnlyEmailSender> logger)
    {
        _logger = logger;
    }

    /// <inheritdoc />
    public Task SendAsync(string toEmail, string subject, string textBody, CancellationToken ct = default)
    {
        _logger.LogWarning(
            "Email delivery is not configured (Email:SmtpHost is empty) — logging instead.\n" +
            "To: {To}\nSubject: {Subject}\n{Body}",
            toEmail, subject, textBody);
        return Task.CompletedTask;
    }
}
