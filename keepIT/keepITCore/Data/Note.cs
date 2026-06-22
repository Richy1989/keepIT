namespace keepITCore.Data;

/// <summary>
/// A single note in a user's grid. One table with a <see cref="Type"/> discriminator; type-specific
/// data (checklist items) hangs off it. Background is a palette <see cref="Color"/> for now —
/// background images and image notes arrive with media handling in a later pass. Scoped to
/// <see cref="OwnerId"/>; soft-deleted via <see cref="IsTrashed"/> to mirror Keep's trash.
/// </summary>
public class Note
{
    public Guid Id { get; set; }

    /// <summary>The user who owns this note. Every query is scoped to the caller's id.</summary>
    public Guid OwnerId { get; set; }

    /// <summary>Navigation to the owning user.</summary>
    public ApplicationUser Owner { get; set; } = null!;

    /// <summary>The note's content type (text, checklist, …).</summary>
    public NoteType Type { get; set; } = NoteType.Text;

    /// <summary>Optional title shown at the top of the card.</summary>
    public string? Title { get; set; }

    /// <summary>Free-form body (text/markdown). Used primarily by <see cref="NoteType.Text"/> notes.</summary>
    public string? Body { get; set; }

    /// <summary>Background color key from the palette (e.g. "rose"). Null = the default canvas.</summary>
    public string? Color { get; set; }

    /// <summary>Pinned notes sort to the top of the grid.</summary>
    public bool IsPinned { get; set; }

    /// <summary>Archived notes are hidden from the main grid but kept (Archive view).</summary>
    public bool IsArchived { get; set; }

    /// <summary>Soft-delete flag (trash). Trashed notes are hidden until purged or restored.</summary>
    public bool IsTrashed { get; set; }

    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAtUtc { get; set; } = DateTime.UtcNow;

    /// <summary>Checklist rows, ordered, populated for <see cref="NoteType.Checklist"/> notes.</summary>
    public ICollection<ChecklistItem> ChecklistItems { get; set; } = new List<ChecklistItem>();

    /// <summary>Per-user list memberships for this note (see <see cref="NoteList"/>).</summary>
    public ICollection<NoteList> NoteLists { get; set; } = new List<NoteList>();
}
