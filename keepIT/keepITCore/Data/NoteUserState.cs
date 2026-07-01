namespace keepITCore.Data;

/// <summary>
/// A user's private view of a note: pinned / archived / trashed are <em>per user</em>, not global
/// (ARCHITECTURE.md — "a note's pinned/archived/trashed state is per user"). On a shared note each
/// collaborator pins/archives/trashes it in their own grid without touching anyone else's view.
/// <para>Existence of a row also means "this note is in this user's grid": the owner gets one when
/// the note is created, and a grantee gets one when they accept a share (removed on revoke). The
/// notes grid query is therefore driven off this table. Composite key (<see cref="NoteId"/>,
/// <see cref="UserId"/>).</para>
/// </summary>
public class NoteUserState
{
    public Guid NoteId { get; set; }

    /// <summary>Navigation to the note this view-state belongs to.</summary>
    public Note Note { get; set; } = null!;

    /// <summary>The user whose private view of the note this row represents.</summary>
    public Guid UserId { get; set; }

    /// <summary>Pinned notes sort to the top of this user's grid.</summary>
    public bool IsPinned { get; set; }

    /// <summary>Hidden from this user's main grid but kept (their Archive view).</summary>
    public bool IsArchived { get; set; }

    /// <summary>This user's soft-delete (trash). Hidden until they restore or the owner purges it.</summary>
    public bool IsTrashed { get; set; }
}
