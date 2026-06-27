using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>
/// Payload to register a new account. The data-annotation attributes are validated automatically by
/// <c>[ApiController]</c> before the action runs (a 400 with these messages); the password's
/// complexity rules (digit, upper/lowercase, symbol) are additionally enforced by ASP.NET Core
/// Identity in <c>AuthController.Register</c>.
/// </summary>
public class RegisterRequestDto
{
    /// <summary>The new account's email; also used as the username and must be unique.</summary>
    [Required(ErrorMessage = "Email is required.")]
    [EmailAddress(ErrorMessage = "Enter a valid email address.")]
    [MaxLength(256)]
    public string Email { get; set; } = null!;

    /// <summary>
    /// The account password. The annotation only checks the length; the full complexity rule
    /// (one digit, an uppercase letter, a lowercase letter, and a symbol) is enforced by Identity.
    /// </summary>
    [Required(ErrorMessage = "Password is required.")]
    [MinLength(8, ErrorMessage = "Password must be at least 8 characters and include one digit, an uppercase letter, a lowercase letter, and a symbol.")]
    [MaxLength(128)]
    public string Password { get; set; } = null!;

    /// <summary>Optional friendly name shown in the UI; falls back to the email when omitted.</summary>
    [MaxLength(100)]
    public string? DisplayName { get; set; }
}
