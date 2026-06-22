using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

public class LoginRequestDto
{
    [Required, EmailAddress, MaxLength(256)]
    public string Email { get; set; } = null!;

    [Required]
    public string Password { get; set; } = null!;
}
