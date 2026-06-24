using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

public class RegisterRequestDto
{
    [Required(ErrorMessage = "Email is required.")]
    [EmailAddress(ErrorMessage = "Enter a valid email address.")]
    [MaxLength(256)]
    public string Email { get; set; } = null!;

    [Required(ErrorMessage = "Password is required.")]
    [MinLength(8, ErrorMessage = "Password must be at least 8 characters and include one digit, an uppercase letter, a lowercase letter, and a symbol.")]
    [MaxLength(128)]
    public string Password { get; set; } = null!;

    [MaxLength(100)]
    public string? DisplayName { get; set; }
}
