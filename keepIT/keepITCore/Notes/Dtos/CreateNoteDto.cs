using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>Payload to create a note.</summary>
public class CreateNoteDto
{
    /// <summary>
    /// The id the client picked for the note, so that sending the same create again (its answer was
    /// lost) returns the note it made instead of making a second one. Kept when no note has it; when
    /// the caller's own note has it, that note is the answer; when someone else's has it, the note
    /// gets a new id. Null: the server picks one.
    /// </summary>
    public Guid? Id { get; set; }

    public NoteType Type { get; set; } = NoteType.Text;

    [MaxLength(NoteLimits.Title)]
    public string? Title { get; set; }

    /// <summary>Free-form body. Capped so a public instance can't be used as a blob store.</summary>
    [MaxLength(NoteLimits.Body)]
    public string? Body { get; set; }

    [MaxLength(NoteLimits.Color)]
    public string? Color { get; set; }

    /// <summary>The background image's name (see <see cref="NoteDto.Background"/>). Null = none.</summary>
    [MaxLength(NoteLimits.Background)]
    [RegularExpression(NoteBackground.Pattern)]
    public string? Background { get; set; }

    /// <summary>Initial checklist rows (for checklist notes). MaxLength bounds the item count.</summary>
    [MaxLength(NoteLimits.ChecklistItems)]
    public List<ChecklistItemDto>? ChecklistItems { get; set; }

    /// <summary>Lists to file the new note into (must be the caller's own lists).</summary>
    public List<Guid>? ListIds { get; set; }
}
