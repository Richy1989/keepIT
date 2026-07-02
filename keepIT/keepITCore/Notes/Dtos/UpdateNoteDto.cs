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

    /// <summary>Free-form body. Capped so a public instance can't be used as a blob store.</summary>
    [MaxLength(100_000)]
    public string? Body { get; set; }

    [MaxLength(32)]
    public string? Color { get; set; }

    /// <summary>The complete new set of checklist rows (replaces existing). MaxLength bounds the item count.</summary>
    [MaxLength(500)]
    public List<ChecklistItemDto>? ChecklistItems { get; set; }
}
