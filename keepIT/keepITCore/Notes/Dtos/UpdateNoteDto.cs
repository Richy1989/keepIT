using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>
/// Replaces a note's editable content. Checklist items are replaced wholesale (send the full set).
/// Pin/archive/trash flags and list membership have their own endpoints.
/// </summary>
public class UpdateNoteDto
{
    public NoteType Type { get; set; }

    [MaxLength(1000)]
    public string? Title { get; set; }

    public string? Body { get; set; }

    [MaxLength(32)]
    public string? Color { get; set; }

    /// <summary>The complete new set of checklist rows (replaces existing).</summary>
    public List<ChecklistItemDto>? ChecklistItems { get; set; }
}
