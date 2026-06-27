namespace keepITCore.Lists.Dtos;

/// <summary>A list as shown in the sidebar, with the caller's active-note count.</summary>
public class ListDto
{
    public Guid Id { get; set; }
    public string Name { get; set; } = "";
    public string? Color { get; set; }

    /// <summary>How many of the caller's non-trashed notes are filed in this list.</summary>
    public int NoteCount { get; set; }

    public DateTime CreatedAtUtc { get; set; }
}
