namespace keepITCore.Auth.Dtos;

/// <summary>
/// A new authenticator key for the user to add to their app, by scanning <see cref="QrCode"/> or
/// typing <see cref="SharedKey"/>. Nothing changes at sign-in until a code from it is confirmed.
/// </summary>
public class TwoFactorSetupDto
{
    /// <summary>The key in groups of four, for typing into the app by hand.</summary>
    public string SharedKey { get; set; } = null!;

    /// <summary>
    /// The <c>otpauth://</c> address the QR code holds. A phone with an authenticator app opens it
    /// directly, which is how to set one up on the same phone.
    /// </summary>
    public string AuthenticatorUri { get; set; } = null!;

    /// <summary>
    /// <see cref="AuthenticatorUri"/> as a QR code: one string per row, top to bottom, <c>'1'</c>
    /// for a dark module and <c>'0'</c> for a light one, light border included. Draw it dark on light
    /// whatever the theme, which is what scanners expect.
    /// </summary>
    public List<string> QrCode { get; set; } = [];
}
