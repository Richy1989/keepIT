using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>
/// Payload to complete a password reset: the email + single-use token from the reset link, and the
/// new password. The token is minted by Identity (<c>GeneratePasswordResetTokenAsync</c>) and is
/// invalidated by a successful reset (the user's security stamp changes) or by its lifespan expiry.
/// </summary>
public class ResetPasswordRequestDto
{
    /// <summary>The account email the reset link was issued for.</summary>
    [Required(ErrorMessage = "Email is required.")]
    [EmailAddress(ErrorMessage = "Not a valid email address.")]
    [MaxLength(256)]
    public string Email { get; set; } = null!;

    /// <summary>The reset token from the emailed link (single-use, time-limited).</summary>
    [Required(ErrorMessage = "Reset token is required.")]
    [MaxLength(2048)]
    public string Token { get; set; } = null!;

    /// <summary>
    /// The new account password. The annotation only checks the length; the full complexity rule
    /// (one digit, an uppercase letter, a lowercase letter, and a symbol) is enforced by Identity.
    /// </summary>
    [Required(ErrorMessage = "Password is required.")]
    [MinLength(8, ErrorMessage = "Password must be at least 8 characters and include one digit, an uppercase letter, a lowercase letter, and a symbol.")]
    [MaxLength(128)]
    public string NewPassword { get; set; } = null!;
}
