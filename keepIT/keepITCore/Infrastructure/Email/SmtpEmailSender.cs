using MailKit.Net.Smtp;
using MailKit.Security;
using Microsoft.Extensions.Options;
using MimeKit;

namespace keepITCore.Infrastructure.Email;

/// <summary>
/// Sends mail over SMTP via MailKit. Registered only when <c>Email:SmtpHost</c> is configured.
/// Opens a connection per send — fine for this app's volume (password resets), and avoids keeping
/// idle SMTP connections alive. The connection is always encrypted unless the operator explicitly
/// allows otherwise (see <see cref="ConnectionSecurity"/>).
/// </summary>
public class SmtpEmailSender : IEmailSender
{
    private readonly EmailOptions _options;
    private readonly string _host;
    private readonly string _from;
    private readonly ILogger<SmtpEmailSender> _logger;

    public SmtpEmailSender(IOptions<EmailOptions> options, ILogger<SmtpEmailSender> logger)
    {
        _options = options.Value;
        // AddAppEmail only registers this implementation when both are present.
        _host = _options.SmtpHost ?? throw new InvalidOperationException("Email:SmtpHost is required.");
        _from = _options.From ?? throw new InvalidOperationException("Email:From is required.");
        _logger = logger;
    }

    /// <inheritdoc />
    public bool DeliversToRecipient => true;

    /// <summary>
    /// How the connection is secured. STARTTLS is required, not opportunistic: with
    /// <see cref="SecureSocketOptions.StartTlsWhenAvailable"/>, anyone on the network path can strip
    /// the server's STARTTLS offer and read the conversation, reset links and SMTP password
    /// included. Only an explicit <see cref="EmailOptions.AllowUnencrypted"/> brings that fallback
    /// back, for a trusted local relay.
    /// </summary>
    /// <param name="options">The SMTP settings.</param>
    public static SecureSocketOptions ConnectionSecurity(EmailOptions options) =>
        !options.UseStartTls ? SecureSocketOptions.SslOnConnect
        : options.AllowUnencrypted ? SecureSocketOptions.StartTlsWhenAvailable
        : SecureSocketOptions.StartTls;

    /// <inheritdoc />
    public async Task SendAsync(string toEmail, string subject, string textBody, CancellationToken ct = default)
    {
        var message = new MimeMessage();
        message.From.Add(MailboxAddress.Parse(_from));
        message.To.Add(MailboxAddress.Parse(toEmail));
        message.Subject = subject;
        message.Body = new TextPart("plain") { Text = textBody };

        using var client = new SmtpClient();
        var security = ConnectionSecurity(_options);
        try
        {
            await client.ConnectAsync(_host, _options.SmtpPort, security, ct);
        }
        catch (NotSupportedException ex) when (security == SecureSocketOptions.StartTls)
        {
            // Refused before any credential or message is sent. The wording reaches the operator
            // both here in the log and in the Settings page's test-email result.
            throw new InvalidOperationException(
                $"The SMTP server {_host}:{_options.SmtpPort} doesn't offer STARTTLS, so keepIT won't " +
                "send over an unencrypted connection. If it supports implicit TLS, use port 465 with " +
                "Email__UseStartTls=false. For a trusted relay on your own network only, set " +
                "Email__AllowUnencrypted=true.",
                ex);
        }

        if (!string.IsNullOrWhiteSpace(_options.SmtpUsername))
            await client.AuthenticateAsync(_options.SmtpUsername, _options.SmtpPassword ?? string.Empty, ct);

        await client.SendAsync(message, ct);
        await client.DisconnectAsync(quit: true, ct);

        _logger.LogInformation("Sent email {Subject} to {To}", subject, toEmail);
    }
}
