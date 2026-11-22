namespace keepITCore.Data;

/// <summary>
/// A note published to the internet: anyone holding the link's token can open the note without an
/// account, read-only or (with <see cref="AllowEdit"/>) able to change its title, text and
/// checklist (ARCHITECTURE.md "Public links"). At most one per note; only its owner makes, changes
/// or revokes it.
/// <para>The token is a credential, so like <see cref="RefreshToken"/> it is looked up by its
/// SHA-256 <see cref="TokenHash"/>. <see cref="TokenProtected"/> is the token encrypted with the
/// server's Data Protection keys, so the owner can copy the link again later; a database dump alone
/// opens nothing.</para>
/// <para>Deliberately not a navigation on <see cref="Note"/>: nothing that loads notes changes.</para>
/// </summary>
public class NotePublicLink
{
    public Guid Id { get; set; }

    /// <summary>The published note. Unique: one link per note. Deleting the note deletes the link.</summary>
    public Guid NoteId { get; set; }

    /// <summary>SHA-256 (Base64) of the token in the link. Unique; what an open is looked up by.</summary>
    public string TokenHash { get; set; } = null!;

    /// <summary>The token itself, encrypted with Data Protection, for showing the owner the link again.</summary>
    public string TokenProtected { get; set; } = null!;

    /// <summary>The password's hash (Identity's PBKDF2 hasher), or null when the link needs none.</summary>
    public string? PasswordHash { get; set; }

    /// <summary>When the link stops working, or null for never.</summary>
    public DateTime? ExpiresAtUtc { get; set; }

    /// <summary>True when visitors may change the note's title, text and checklist.</summary>
    public bool AllowEdit { get; set; }

    /// <summary>
    /// Bumped on every change to the link. A visitor's view ticket names the revision it was issued
    /// for, so changing the password, turning editing off or making a new token ends every open view.
    /// </summary>
    public int Revision { get; set; }

    /// <summary>The user who published the note (its owner).</summary>
    public Guid CreatedByUserId { get; set; }

    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;

    /// <summary>True once the link has passed its expiry instant.</summary>
    public bool IsExpired(DateTime nowUtc) => ExpiresAtUtc is { } at && nowUtc >= at;
}
