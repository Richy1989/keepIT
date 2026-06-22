using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

public class RegisterRequestDto
{
    [Required, EmailAddress, MaxLength(256)]
    public string Email { get; set; } = null!;

    [Required, MinLength(8), MaxLength(128)]
    public string Password { get; set; } = null!;

    [MaxLength(100)]
    public string? DisplayName { get; set; }
}
