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

    [MaxLength(NoteLimits.Title)]
    public string? Title { get; set; }

    /// <summary>Free-form body. Capped so a public instance can't be used as a blob store.</summary>
    [MaxLength(NoteLimits.Body)]
    public string? Body { get; set; }

    [MaxLength(NoteLimits.Color)]
    public string? Color { get; set; }

    /// <summary>The complete new set of checklist rows (replaces existing). MaxLength bounds the item count.</summary>
    [MaxLength(NoteLimits.ChecklistItems)]
    public List<ChecklistItemDto>? ChecklistItems { get; set; }

    /// <summary>
    /// Which of the fields above this update sets; the rest keep what the note has now. Null sets
    /// them all, as an update always did. An edit made offline replays long after it was made, and
    /// sending the whole note then undid whatever had changed meanwhile in the parts it never
    /// touched — a collaborator's new title reverted by an old edit to the text. Naming the fields
    /// makes concurrent edits last-writer-wins per field instead of per note.
    /// </summary>
    public List<NoteField>? Fields { get; set; }
}
