using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>
/// Starts setting up an authenticator app. The password is asked for again, so a device left
/// signed in can't be used to tie the account to someone else's phone.
/// </summary>
public class TwoFactorSetupRequestDto
{
    /// <summary>The account's current password.</summary>
    [Required(ErrorMessage = "Enter your password to set up two-factor authentication.")]
    [MaxLength(128)]
    public string Password { get; set; } = null!;
}
