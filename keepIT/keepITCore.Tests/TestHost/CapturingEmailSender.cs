using System.Collections.Concurrent;
using keepITCore.Infrastructure.Email;

namespace keepITCore.Tests.TestHost;

/// <summary>
/// Stands in for the API's email sender and keeps every message instead of sending it.
/// <paramref name="deliversToRecipient"/> picks which real sender it impersonates: true behaves like
/// SMTP (the message would reach a user's inbox), false like the log-only fallback.
/// </summary>
public sealed class CapturingEmailSender(bool deliversToRecipient) : IEmailSender
{
    /// <summary>One message the API asked to send.</summary>
    public sealed record Message(string To, string Subject, string Body);

    public ConcurrentQueue<Message> Sent { get; } = new();

    public bool DeliversToRecipient => deliversToRecipient;

    public Task SendAsync(string toEmail, string subject, string textBody, CancellationToken ct = default)
    {
        Sent.Enqueue(new Message(toEmail, subject, textBody));
        return Task.CompletedTask;
    }
}
