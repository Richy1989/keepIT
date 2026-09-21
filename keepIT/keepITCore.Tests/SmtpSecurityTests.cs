using keepITCore.Infrastructure.Email;
using keepITCore.Tests.TestHost;
using MailKit.Security;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace keepITCore.Tests;

/// <summary>
/// Outgoing mail stays encrypted. Opportunistic STARTTLS continued in plain text whenever the server
/// didn't offer it — and anyone on the network path can make it look that way by stripping the
/// offer, then read the reset links and the SMTP password. STARTTLS is now required unless the
/// operator explicitly allows otherwise.
/// </summary>
public sealed class SmtpSecurityTests
{
    private const string Password = "smtp-s3cret";

    private static SmtpEmailSender Sender(int port, bool allowUnencrypted = false) => new(
        Options.Create(new EmailOptions
        {
            SmtpHost = "127.0.0.1",
            SmtpPort = port,
            From = "keepIT <no-reply@example.com>",
            SmtpUsername = "keepit",
            SmtpPassword = Password,
            AllowUnencrypted = allowUnencrypted,
        }),
        NullLogger<SmtpEmailSender>.Instance);

    private static Task SendResetMailAsync(SmtpEmailSender sender) =>
        sender.SendAsync("user@example.com", "Reset your keepIT password", "https://notes.example.com/reset-password?token=abc");

    [Fact]
    public async Task A_server_without_STARTTLS_gets_neither_the_password_nor_the_message()
    {
        await using var server = new FakeSmtpServer();

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() => SendResetMailAsync(Sender(server.Port)));

        Assert.Contains("STARTTLS", error.Message);
        Assert.Contains(server.Received, line => line.StartsWith("EHLO", StringComparison.Ordinal));
        Assert.DoesNotContain(server.Received, line => line.StartsWith("AUTH", StringComparison.Ordinal));
        Assert.DoesNotContain(server.Received, line => line.StartsWith("MAIL", StringComparison.Ordinal));
        Assert.DoesNotContain(server.Received, line => line.Contains("reset-password", StringComparison.Ordinal));
    }

    [Fact]
    public async Task An_explicit_opt_in_still_reaches_a_plain_text_relay()
    {
        await using var server = new FakeSmtpServer();

        await SendResetMailAsync(Sender(server.Port, allowUnencrypted: true));

        Assert.Contains(server.Received, line => line.StartsWith("AUTH", StringComparison.Ordinal));
        Assert.Contains(server.Received, line => line.Contains("reset-password", StringComparison.Ordinal));
    }

    [Theory]
    [InlineData(true, false, SecureSocketOptions.StartTls)]
    [InlineData(true, true, SecureSocketOptions.StartTlsWhenAvailable)]
    [InlineData(false, false, SecureSocketOptions.SslOnConnect)]
    [InlineData(false, true, SecureSocketOptions.SslOnConnect)]
    public void The_connection_is_only_left_unencrypted_when_explicitly_allowed(
        bool useStartTls, bool allowUnencrypted, SecureSocketOptions expected)
    {
        var options = new EmailOptions { UseStartTls = useStartTls, AllowUnencrypted = allowUnencrypted };

        Assert.Equal(expected, SmtpEmailSender.ConnectionSecurity(options));
    }
}
