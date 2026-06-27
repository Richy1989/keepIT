namespace keepITCore.Data;

/// <summary>One checkbox row inside a <see cref="NoteType.Checklist"/> note.</summary>
public class ChecklistItem
{
    public Guid Id { get; set; }

    public Guid NoteId { get; set; }

    /// <summary>Navigation back to the owning note.</summary>
    public Note Note { get; set; } = null!;

    /// <summary>The item's label.</summary>
    public string Text { get; set; } = "";

    /// <summary>Whether the box is ticked.</summary>
    public bool IsChecked { get; set; }

    /// <summary>Sort position within the note (ascending).</summary>
    public int Order { get; set; }
}
