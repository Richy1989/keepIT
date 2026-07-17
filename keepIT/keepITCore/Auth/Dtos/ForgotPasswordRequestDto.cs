using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>
/// Payload to request a password-reset link. The endpoint responds identically whether or not an
/// account exists for the address, so this reveals nothing about registered emails.
/// </summary>
public class ForgotPasswordRequestDto
{
    /// <summary>The account email to send the reset link to.</summary>
    [Required(ErrorMessage = "Email is required.")]
    [EmailAddress(ErrorMessage = "Not a valid email address.")]
    [MaxLength(256)]
    public string Email { get; set; } = null!;
}
