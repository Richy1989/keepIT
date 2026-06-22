using System.ComponentModel.DataAnnotations;

namespace keepITCore.Notes.Dtos;

/// <summary>A checklist row, as returned to and accepted from the client.</summary>
public class ChecklistItemDto
{
    /// <summary>Existing item id. Leave null when adding a new item.</summary>
    public Guid? Id { get; set; }

    [MaxLength(2000)]
    public string Text { get; set; } = "";

    public bool IsChecked { get; set; }

    /// <summary>Sort position within the note (ascending).</summary>
    public int Order { get; set; }
}
