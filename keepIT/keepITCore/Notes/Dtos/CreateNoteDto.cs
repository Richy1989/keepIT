using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>Payload to create a note.</summary>
public class CreateNoteDto
{
    public NoteType Type { get; set; } = NoteType.Text;

    [MaxLength(1000)]
    public string? Title { get; set; }

    public string? Body { get; set; }

    [MaxLength(32)]
    public string? Color { get; set; }

    /// <summary>Initial checklist rows (for checklist notes).</summary>
    public List<ChecklistItemDto>? ChecklistItems { get; set; }

    /// <summary>Lists to file the new note into (must be the caller's own lists).</summary>
    public List<Guid>? ListIds { get; set; }
}
