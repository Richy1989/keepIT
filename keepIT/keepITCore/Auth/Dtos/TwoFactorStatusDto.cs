namespace keepITCore.Auth.Dtos;

/// <summary>Whether the signed-in user's account asks for an authenticator code at sign-in.</summary>
public class TwoFactorStatusDto
{
    public bool Enabled { get; set; }

    /// <summary>Recovery codes not yet used; each signs in once in place of a code. 0 while off.</summary>
    public int RecoveryCodesLeft { get; set; }
}
