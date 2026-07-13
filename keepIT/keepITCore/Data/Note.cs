namespace keepITCore.Data;

/// <summary>
/// A single note. One table with a <see cref="Type"/> discriminator; type-specific data (checklist
/// items) hangs off it. Background is a palette <see cref="Color"/> for now — background images and
/// image notes arrive with media handling in a later pass. Owned by <see cref="OwnerId"/> and
/// optionally shared with others via <see cref="NoteShares"/>. Per-user view state (pin/archive/
/// trash) lives in <see cref="UserStates"/>, not on the note, so a collaborator's pin or trash is
/// private to their own grid (ARCHITECTURE.md "Sharing / collaboration").
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

    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAtUtc { get; set; } = DateTime.UtcNow;

    /// <summary>Checklist rows, ordered, populated for <see cref="NoteType.Checklist"/> notes.</summary>
    public ICollection<ChecklistItem> ChecklistItems { get; set; } = new List<ChecklistItem>();

    /// <summary>Per-user list memberships for this note (see <see cref="NoteList"/>).</summary>
    public ICollection<NoteList> NoteLists { get; set; } = new List<NoteList>();

    /// <summary>Per-user pin/archive/trash view state (see <see cref="NoteUserState"/>).</summary>
    public ICollection<NoteUserState> UserStates { get; set; } = new List<NoteUserState>();

    /// <summary>Non-owner grants of access to this note (see <see cref="NoteShare"/>).</summary>
    public ICollection<NoteShare> NoteShares { get; set; } = new List<NoteShare>();

    /// <summary>Per-user reminders on this note (see <see cref="NoteReminder"/>).</summary>
    public ICollection<NoteReminder> Reminders { get; set; } = new List<NoteReminder>();
}
