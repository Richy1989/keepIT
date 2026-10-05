using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>
/// Confirms a change to two-factor authentication once it is on — turning it off, or replacing
/// the recovery codes — with both factors: the password, and a code from the app or a recovery code.
/// </summary>
public class TwoFactorConfirmRequestDto
{
    /// <summary>The account's current password.</summary>
    [Required(ErrorMessage = "Enter your password.")]
    [MaxLength(128)]
    public string Password { get; set; } = null!;

    /// <summary>A code from the authenticator app, or one of the recovery codes (which is then used up).</summary>
    [Required(ErrorMessage = "Enter a code from your authenticator app, or a recovery code.")]
    [MaxLength(64)]
    public string Code { get; set; } = null!;
}
