namespace keepITCore.Data;

/// <summary>
/// A long-lived, opaque refresh token, stored server-side so it can be rotated and revoked.
/// We persist only a SHA-256 <see cref="TokenHash"/> of the token — never the raw value — so a
/// database leak can't be replayed. The raw token lives only in the client's httpOnly cookie.
/// </summary>
public class RefreshToken
{
    public Guid Id { get; set; }

    public Guid UserId { get; set; }
    public ApplicationUser User { get; set; } = null!;

    /// <summary>SHA-256 hash (Base64) of the opaque token handed to the client.</summary>
    public string TokenHash { get; set; } = null!;

    public DateTime ExpiresAtUtc { get; set; }
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;

    /// <summary>Set when the token is revoked (logout, or rotated on refresh).</summary>
    public DateTime? RevokedAtUtc { get; set; }

    /// <summary>On rotation, the hash of the token that replaced this one (audit trail).</summary>
    public string? ReplacedByTokenHash { get; set; }

    /// <summary>True once the token has passed its expiry instant.</summary>
    public bool IsExpired => DateTime.UtcNow >= ExpiresAtUtc;

    /// <summary>True while the token is still usable: neither revoked nor expired.</summary>
    public bool IsActive => RevokedAtUtc is null && !IsExpired;
}
