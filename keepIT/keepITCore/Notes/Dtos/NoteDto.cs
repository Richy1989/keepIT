using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>
/// A note returned to the client. Content is shared across collaborators, but the pin/archive/trash
/// flags and <see cref="ListIds"/> are resolved <em>for the calling user</em> (their private view),
/// and the access fields (<see cref="IsOwner"/>, <see cref="Role"/>, <see cref="CanEdit"/>) tell the
/// UI what this caller may do with it.
/// </summary>
public class NoteDto
{
    public Guid Id { get; set; }
    public NoteType Type { get; set; }
    public string? Title { get; set; }
    public string? Body { get; set; }
    public string? Color { get; set; }

    /// <summary>The caller's private pin/archive/trash view (from their <see cref="NoteUserState"/>).</summary>
    public bool IsPinned { get; set; }
    public bool IsArchived { get; set; }
    public bool IsTrashed { get; set; }

    /// <summary>The caller's reminder on this note (per-user, like the flags above). Null = none set.</summary>
    public DateTime? RemindAtUtc { get; set; }

    /// <summary>The reminder's recurrence; null when no reminder is set.</summary>
    public ReminderRecurrence? ReminderRecurrence { get; set; }

    /// <summary>True when a one-time reminder has already fired (renders as past until cleared or rescheduled).</summary>
    public bool ReminderFired { get; set; }

    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }

    /// <summary>True when the caller owns this note (as opposed to it being shared with them).</summary>
    public bool IsOwner { get; set; }

    /// <summary>The caller's collaborator role when the note is shared with them; null when they own it.</summary>
    public NoteRole? Role { get; set; }

    /// <summary>Whether the caller may edit content (owner or Editor). Convenience for the UI.</summary>
    public bool CanEdit { get; set; }

    /// <summary>True when the caller owns the note and it is shared with at least one other user.</summary>
    public bool IsShared { get; set; }

    /// <summary>Checklist rows, ordered. Empty for non-checklist notes.</summary>
    public List<ChecklistItemDto> ChecklistItems { get; set; } = new();

    /// <summary>Ids of the caller's lists this note belongs to.</summary>
    public List<Guid> ListIds { get; set; } = new();
}
