using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>
/// Payload to delete the signed-in user's account. The caller is taken from the access token; the
/// password is asked for again so a device left signed in, or a stolen access token, can't erase an
/// account on its own.
/// </summary>
public class DeleteAccountRequestDto
{
    /// <summary>The account's current password.</summary>
    [Required(ErrorMessage = "Enter your password to delete your account.")]
    [MaxLength(128)]
    public string Password { get; set; } = null!;
}
