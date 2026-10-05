namespace keepITCore.Auth.Dtos;

/// <summary>
/// Why a sign-in was refused (401). Wrong credentials and a locked account read the same, so the
/// answer never tells which emails are registered. <see cref="TwoFactorRequired"/> is only ever set
/// once the password was right: the client then asks for the authenticator code and sends the
/// sign-in again with it.
/// </summary>
public class LoginFailureDto
{
    /// <summary>A message to show.</summary>
    public string Error { get; set; } = null!;

    /// <summary>The password was right, and this account also needs a code from its authenticator app.</summary>
    public bool TwoFactorRequired { get; set; }
}
