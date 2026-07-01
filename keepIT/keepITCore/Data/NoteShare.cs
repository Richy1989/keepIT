namespace keepITCore.Data;

/// <summary>
/// Grants one user (<see cref="GranteeId"/>) access to one note at a <see cref="Role"/>. This is the
/// single source of truth for "who besides the owner can see/edit a note" (ARCHITECTURE.md). The
/// owner is implicit and never has a row here. A share exists only once the recipient has
/// <em>accepted</em> a <see cref="ShareInviteNotification"/>; a pending invite is not yet a share.
/// One row per (note, grantee) — enforced by a unique index.
/// </summary>
public class NoteShare
{
    public Guid Id { get; set; }

    /// <summary>The shared note.</summary>
    public Guid NoteId { get; set; }

    /// <summary>Navigation to the shared note.</summary>
    public Note Note { get; set; } = null!;

    /// <summary>The user the note is shared with.</summary>
    public Guid GranteeId { get; set; }

    /// <summary>Navigation to the grantee.</summary>
    public ApplicationUser Grantee { get; set; } = null!;

    /// <summary>The grantee's permission on the note (viewer/editor).</summary>
    public NoteRole Role { get; set; } = NoteRole.Viewer;

    /// <summary>The user who created the share (the note's owner at grant time).</summary>
    public Guid CreatedByUserId { get; set; }

    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
}
