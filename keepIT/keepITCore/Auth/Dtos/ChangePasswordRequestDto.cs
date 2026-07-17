using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>
/// Payload to change the password of a registered user. The caller's identity comes from the
/// access token — deliberately no user id in the body, so the endpoint can't be aimed at anyone
/// else's account.
/// </summary>
public class ChangePasswordRequestDto
{
    /// <summary>
    /// The new account password. The annotation only checks the length; the full complexity rule
    /// (one digit, an uppercase letter, a lowercase letter, and a symbol) is enforced by Identity.
    /// </summary>
    [Required(ErrorMessage = "Password is required.")]
    [MinLength(8, ErrorMessage = "Password must be at least 8 characters and include one digit, an uppercase letter, a lowercase letter, and a symbol.")]
    [MaxLength(128)]
    public string NewPassword { get; set; } = null!;

    [MaxLength(128)]
    public string CurrentPassword { get; set; } = null!;
}
