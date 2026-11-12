using keepITCore.Data;

namespace keepITCore.Notes;

/// <summary>
/// The single-level checklist model shared by the note endpoints and the archive importer: an item
/// is either top-level (<see cref="ChecklistItem.Indent"/> 0) or the sub-item of the top-level item
/// above it (1).
/// </summary>
public static class ChecklistHierarchy
{
    /// <summary>
    /// Puts <paramref name="items"/> (in display order) into the single-level model and derives the
    /// checked state of every parent that has children.
    /// <para>
    /// Indentation: the first item is always top-level; a requested indent above 0 is kept as 1 only
    /// when a top-level item precedes the row, and falls back to 0 otherwise. That turns an orphaned
    /// sub-item into a top-level row rather than leaving it under a parent that isn't there, and
    /// collapses any deeper nesting a client may have sent.
    /// </para>
    /// <para>
    /// Checked state: the sub-items of a top-level item are the indent-1 rows immediately below it,
    /// up to the next top-level row. A parent with at least one child is checked if and only if all
    /// of its children are; a parent without children keeps its own state, as does every item's own
    /// box. Children are never ticked from the parent here — the client sends their explicit states.
    /// </para>
    /// </summary>
    /// <param name="items">The rows to normalise in place, in display order.</param>
    public static void Normalize(IReadOnlyList<ChecklistItem> items)
    {
        // Indentation first: the checked-state pass below reads the normalised hierarchy.
        var seenTopLevel = false;
        foreach (var item in items)
        {
            item.Indent = item.Indent > 0 && seenTopLevel ? 1 : 0;
            if (item.Indent == 0) seenTopLevel = true;
        }

        for (var i = 0; i < items.Count; i++)
        {
            if (items[i].Indent != 0) continue;

            var hasChildren = false;
            var allChecked = true;
            for (var j = i + 1; j < items.Count && items[j].Indent == 1; j++)
            {
                hasChildren = true;
                allChecked &= items[j].IsChecked;
            }

            if (hasChildren) items[i].IsChecked = allChecked;
        }
    }
}
