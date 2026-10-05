using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

public class LoginRequestDto
{
    [Required, EmailAddress, MaxLength(256)]
    public string Email { get; set; } = null!;

    [Required]
    public string Password { get; set; } = null!;

    /// <summary>
    /// A code from the authenticator app, or a recovery code, for an account with two-factor
    /// authentication on. Sent once the first attempt came back with
    /// <see cref="LoginFailureDto.TwoFactorRequired"/>; ignored for other accounts.
    /// </summary>
    [MaxLength(64)]
    public string? TwoFactorCode { get; set; }
}
