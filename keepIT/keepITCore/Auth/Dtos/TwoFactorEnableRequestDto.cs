using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>Turns two-factor authentication on, with a code that shows the authenticator app works.</summary>
public class TwoFactorEnableRequestDto
{
    /// <summary>The six-digit code the app shows now.</summary>
    [Required(ErrorMessage = "Enter the code your authenticator app shows.")]
    [MaxLength(16)]
    public string Code { get; set; } = null!;
}
