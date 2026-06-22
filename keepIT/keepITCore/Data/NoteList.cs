namespace keepITCore.Data;

/// <summary>
/// Per-user join between a note and a list (ARCHITECTURE.md). The <see cref="UserId"/> makes list
/// membership private: on a shared note each collaborator files it into their own lists. Until
/// sharing ships, <see cref="UserId"/> always equals the note's owner. Composite key
/// (<see cref="NoteId"/>, <see cref="ListId"/>, <see cref="UserId"/>).
/// </summary>
public class NoteList
{
    public Guid NoteId { get; set; }

    /// <summary>Navigation to the note.</summary>
    public Note Note { get; set; } = null!;

    public Guid ListId { get; set; }

    /// <summary>Navigation to the list.</summary>
    public KeepList List { get; set; } = null!;

    /// <summary>The user this membership belongs to (keeps lists private per user).</summary>
    public Guid UserId { get; set; }

    public DateTime AddedAtUtc { get; set; } = DateTime.UtcNow;
}
