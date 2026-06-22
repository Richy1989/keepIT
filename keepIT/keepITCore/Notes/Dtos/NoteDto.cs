using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>A note returned to the client, including its checklist items and the caller's list memberships.</summary>
public class NoteDto
{
    public Guid Id { get; set; }
    public NoteType Type { get; set; }
    public string? Title { get; set; }
    public string? Body { get; set; }
    public string? Color { get; set; }
    public bool IsPinned { get; set; }
    public bool IsArchived { get; set; }
    public bool IsTrashed { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }

    /// <summary>Checklist rows, ordered. Empty for non-checklist notes.</summary>
    public List<ChecklistItemDto> ChecklistItems { get; set; } = new();

    /// <summary>Ids of the caller's lists this note belongs to.</summary>
    public List<Guid> ListIds { get; set; } = new();
}
