using System.ComponentModel.DataAnnotations;

namespace keepITCore.Auth.Dtos;

/// <summary>
/// Payload to change the signed-in user's profile. The caller's identity comes from the access
/// token — deliberately no user id in the body, so the endpoint can't be aimed at anyone else's
/// account.
/// </summary>
public class UpdateProfileRequestDto
{
    /// <summary>
    /// The new display name. Surrounding whitespace is trimmed; null, empty or blank removes the
    /// name, and the clients fall back to the email — the same as an account registered without one.
    /// </summary>
    [MaxLength(100, ErrorMessage = "Display name can be at most 100 characters.")]
    public string? DisplayName { get; set; }
}
