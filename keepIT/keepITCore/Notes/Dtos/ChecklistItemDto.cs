using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>A checklist row, as returned to and accepted from the client.</summary>
public class ChecklistItemDto
{
    /// <summary>Existing item id. Leave null when adding a new item.</summary>
    public Guid? Id { get; set; }

    [MaxLength(NoteLimits.ChecklistItemText)]
    public string Text { get; set; } = "";

    /// <summary>
    /// Nesting depth: 0 for a top-level row, 1 for a sub-item. The server normalises it on save —
    /// the first row is always 0, anything deeper than one level becomes 1, and an indented row with
    /// no top-level row above it falls back to 0 — so the value returned may differ from the one sent.
    /// <para>Always set in a response. In a request, null (or absent) keeps the row's indent as it
    /// is, and a new row starts at 0: an app from before sub-items sends none, and must not flatten
    /// what a newer one nested.</para>
    /// </summary>
    public int? Indent { get; set; }

    public bool IsChecked { get; set; }

    /// <summary>Sort position within the note (ascending).</summary>
    public int Order { get; set; }
}
