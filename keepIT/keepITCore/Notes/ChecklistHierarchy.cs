using keepITCore.Data;

namespace keepITCore.Notes;

/// <summary>
/// The single-level checklist model shared by the note endpoints and the archive importer: an item
/// is either top-level (<see cref="ChecklistItem.Indent"/> 0) or the sub-item of the top-level item
/// above it (1).
/// <para>Only the indent is held to the model here. Ticking a parent's group, and a parent following
/// its children, are the apps' rules: the server stores every box as it is sent, because an app from
/// before sub-items can't see them, and a parent it ticks must stay ticked rather than spring back.</para>
/// </summary>
public static class ChecklistHierarchy
{
    /// <summary>
    /// Puts <paramref name="items"/> (in display order) into the single-level model: the first item
    /// is always top-level; a requested indent above 0 is kept as 1 only when a top-level item
    /// precedes the row, and falls back to 0 otherwise. That turns an orphaned sub-item into a
    /// top-level row rather than leaving it under a parent that isn't there, and collapses any deeper
    /// nesting a client may have sent.
    /// </summary>
    /// <param name="items">The rows to normalise in place, in display order.</param>
    public static void Normalize(IReadOnlyList<ChecklistItem> items)
    {
        var seenTopLevel = false;
        foreach (var item in items)
        {
            item.Indent = item.Indent > 0 && seenTopLevel ? 1 : 0;
            if (item.Indent == 0) seenTopLevel = true;
        }
    }
}
