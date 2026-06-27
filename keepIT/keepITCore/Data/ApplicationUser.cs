using Microsoft.AspNetCore.Identity;

namespace keepITCore.Data;

/// <summary>
/// The application user. Identity handles email, password hash, security stamp, etc.;
/// the <see cref="Id"/> (a Guid) is the <c>ownerId</c> every note/list/media will be scoped to.
/// </summary>
public class ApplicationUser : IdentityUser<Guid>
{
    /// <summary>Friendly name shown in the UI. Optional; falls back to the email.</summary>
    public string? DisplayName { get; set; }

    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;

    public string? ProfileImageFileName { get; set; } = null;

    /// <summary>Refresh tokens issued to this user across their devices.</summary>
    public ICollection<RefreshToken> RefreshTokens { get; set; } = new List<RefreshToken>();
}
