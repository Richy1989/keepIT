namespace keepITCore.Auth.Dtos;

/// <summary>
/// A new set of recovery codes, shown once: the server keeps only their hashes. Each one signs in
/// once in place of an authenticator code, for when the phone is lost.
/// </summary>
public class TwoFactorRecoveryCodesDto
{
    public List<string> Codes { get; set; } = [];
}
