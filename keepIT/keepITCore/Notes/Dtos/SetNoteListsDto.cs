namespace keepITCore.Notes.Dtos;

/// <summary>Replaces the complete set of lists a note belongs to, for the caller.</summary>
public class SetNoteListsDto
{
    /// <summary>The lists the note should belong to (the caller's own lists).</summary>
    public List<Guid> ListIds { get; set; } = new();
}
