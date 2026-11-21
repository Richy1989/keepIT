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

    /// <summary>
    /// The background image's name (see <see cref="NoteDto.Background"/>). Null = none. Set only
    /// when <see cref="Fields"/> names <see cref="NoteField.Background"/>.
    /// </summary>
    [MaxLength(NoteLimits.Background)]
    [RegularExpression(NoteBackground.Pattern)]
    public string? Background { get; set; }

    /// <summary>The complete new set of checklist rows (replaces existing). MaxLength bounds the item count.</summary>
    [MaxLength(NoteLimits.ChecklistItems)]
    public List<ChecklistItemDto>? ChecklistItems { get; set; }

    /// <summary>
    /// The checklist rows this edit started from, as the client had them. When given, the update
    /// doesn't replace the list with <see cref="ChecklistItems"/>: only what changed between the two
    /// (rows ticked, renamed, indented, added, deleted or moved) is applied to the list as it is now,
    /// so what other people changed meanwhile stays (see <c>ChecklistMerge</c>). Null replaces the
    /// list whole, as an update always did. Ignored unless the update sets the checklist, and when
    /// it switches the note between text and checklist.
    /// </summary>
    [MaxLength(NoteLimits.ChecklistItems)]
    public List<ChecklistItemDto>? ChecklistBase { get; set; }

    /// <summary>
    /// Which of the fields above this update sets; the rest keep what the note has now. Null sets
    /// them all, as an update always did. An edit made offline replays long after it was made, and
    /// sending the whole note then undid whatever had changed meanwhile in the parts it never
    /// touched — a collaborator's new title reverted by an old edit to the text. Naming the fields
    /// makes concurrent edits last-writer-wins per field instead of per note.
    /// <para>
    /// "Them all" means the fields an update had before 1.3: <see cref="Background"/> is set only
    /// when named. An app older than the field sends no background, and an update without fields
    /// from it would otherwise wipe the background a newer client chose.
    /// </para>
    /// </summary>
    public List<NoteField>? Fields { get; set; }
}
