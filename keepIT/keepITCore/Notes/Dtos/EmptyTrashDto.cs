namespace keepITCore.Notes.Dtos;

/// <summary>Empties the caller's trash of the notes they saw there.</summary>
public class EmptyTrashDto
{
    /// <summary>
    /// The trashed notes to remove, as the client listed them. Ids no longer in the caller's
    /// trash are skipped.
    /// </summary>
    public List<Guid> NoteIds { get; set; } = new();
}
