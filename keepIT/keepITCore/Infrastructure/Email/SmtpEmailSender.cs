using MailKit.Net.Smtp;
using MailKit.Security;
using Microsoft.Extensions.Options;
using MimeKit;

namespace keepITCore.Infrastructure.Email;

/// <summary>
/// Sends mail over SMTP via MailKit. Registered only when <c>Email:SmtpHost</c> is configured.
/// Opens a connection per send — fine for this app's volume (password resets), and avoids keeping
/// idle SMTP connections alive.
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
    public async Task SendAsync(string toEmail, string subject, string textBody, CancellationToken ct = default)
    {
        var message = new MimeMessage();
        message.From.Add(MailboxAddress.Parse(_from));
        message.To.Add(MailboxAddress.Parse(toEmail));
        message.Subject = subject;
        message.Body = new TextPart("plain") { Text = textBody };

        using var client = new SmtpClient();
        var security = _options.UseStartTls
            ? SecureSocketOptions.StartTlsWhenAvailable
            : SecureSocketOptions.SslOnConnect;
        await client.ConnectAsync(_host, _options.SmtpPort, security, ct);

        if (!string.IsNullOrWhiteSpace(_options.SmtpUsername))
            await client.AuthenticateAsync(_options.SmtpUsername, _options.SmtpPassword ?? string.Empty, ct);

        await client.SendAsync(message, ct);
        await client.DisconnectAsync(quit: true, ct);

        _logger.LogInformation("Sent email {Subject} to {To}", subject, toEmail);
    }
}
