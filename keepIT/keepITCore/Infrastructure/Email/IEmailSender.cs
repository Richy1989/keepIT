namespace keepITCore.Infrastructure.Email;

/// <summary>
/// Outbound email. Exactly one implementation is registered at startup based on config:
/// <see cref="SmtpEmailSender"/> when <c>Email:SmtpHost</c> is set, otherwise
/// <see cref="LogOnlyEmailSender"/> (writes the message to the server log). Callers never need to
/// know which — "send" always succeeds in the sense that the message went <em>somewhere</em> the
/// operator can reach.
/// </summary>
public interface IEmailSender
{
    /// <summary>Sends a plain-text email.</summary>
    /// <param name="toEmail">Recipient address.</param>
    /// <param name="subject">Subject line.</param>
    /// <param name="textBody">Plain-text body.</param>
    /// <param name="ct">Cancellation token.</param>
    Task SendAsync(string toEmail, string subject, string textBody, CancellationToken ct = default);
}
