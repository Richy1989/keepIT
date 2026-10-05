using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>Payload to create a note.</summary>
public class CreateNoteDto
{
    public NoteType Type { get; set; } = NoteType.Text;

    [MaxLength(NoteLimits.Title)]
    public string? Title { get; set; }

    /// <summary>Free-form body. Capped so a public instance can't be used as a blob store.</summary>
    [MaxLength(NoteLimits.Body)]
    public string? Body { get; set; }

    [MaxLength(NoteLimits.Color)]
    public string? Color { get; set; }

    /// <summary>Initial checklist rows (for checklist notes). MaxLength bounds the item count.</summary>
    [MaxLength(NoteLimits.ChecklistItems)]
    public List<ChecklistItemDto>? ChecklistItems { get; set; }

    /// <summary>Lists to file the new note into (must be the caller's own lists).</summary>
    public List<Guid>? ListIds { get; set; }
}
