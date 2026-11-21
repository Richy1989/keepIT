using keepITCore.Notes.Dtos;

namespace keepITCore.Notes;

/// <summary>
/// Merges one edit of a checklist into what the note holds now, row by row.
/// <para>
/// A checklist is shared, and the people on it edit it at once: two of them in a shop, both ticking,
/// one adding the thing they forgot. Each editor sends its whole list, and replacing the note's list
/// with it undid every change made meanwhile by anyone else — a tick unticked, a new row deleted, a
/// deleted row back. So an edit also says which rows it started from (<see cref="UpdateNoteDto.ChecklistBase"/>),
/// and only the difference between the two is applied: what it ticked, renamed, indented, added,
/// deleted or moved. A row changed by both keeps each one's change to a different property, and the
/// later one's to the same property.
/// </para>
/// <para>
/// Rows are matched by id, so a new row should arrive with an id of its own (the apps make one up):
/// then sending the same edit twice, as an outbox does after a lost response, adds it once.
/// A row this edit changed that someone else has deleted stays deleted. A base with a row that has
/// no id can't be matched, so that edit replaces the list as before.
/// </para>
/// <para>
/// The cases are in <c>web/src/features/notes/checklistMerge.json</c>, which the web's and the
/// Android app's copies of this merge are tested against too.
/// </para>
/// Pure, so the rules are testable without a database; the Android app runs the same rules on its
/// cache (<c>ChecklistMerge.kt</c>) so an edit still waiting to sync shows as the server will apply it.
/// </summary>
public static class ChecklistMerge
{
    /// <summary>
    /// The checklist that results from applying the change <paramref name="baseRows"/> →
    /// <paramref name="incoming"/> to <paramref name="current"/>, in order. Rows of
    /// <paramref name="current"/> keep their id; new rows carry the id they were sent with, or none.
    /// </summary>
    /// <param name="baseRows">The rows the edit started from.</param>
    /// <param name="incoming">The rows the edit ends with.</param>
    /// <param name="current">The rows the note holds now (every one has an id).</param>
    public static List<ChecklistItemDto> Merge(
        IReadOnlyList<ChecklistItemDto> baseRows,
        IReadOnlyList<ChecklistItemDto> incoming,
        IReadOnlyList<ChecklistItemDto> current)
    {
        // A row the edit started from without an id (a note shown before its first refetch) can't
        // be matched, and merging would keep the note's copy of each row as well as the edit's: a
        // list twice over. The edit replaces the list instead, as every edit used to.
        if (baseRows.Any(r => r.Id is null)) return incoming.Select(r => Copy(r, r.Id)).ToList();

        var baseById = ById(baseRows);
        var incomingById = ById(incoming);
        var currentIds = current.Where(c => c.Id is not null).Select(c => c.Id!.Value).ToHashSet();

        // What survives of the rows the note has now, with this edit's changes laid over them.
        var merged = new List<ChecklistItemDto>();
        foreach (var row in current)
        {
            if (row.Id is not { } id) continue;
            var inBase = baseById.TryGetValue(id, out var before);
            var inEdit = incomingById.TryGetValue(id, out var after);
            if (inBase && !inEdit) continue; // this edit deleted it
            if (inBase) merged.Add(Changed(row, before!, after!));
            else if (inEdit) merged.Add(Copy(after!, id)); // this edit's own new row, sent again
            else merged.Add(Copy(row, id)); // someone else's new row
        }

        // This edit's new rows: no id, or one neither its base nor the note knows. Rows the edit
        // started from that are gone from the note were deleted by someone else, and stay gone.
        bool IsAddition(ChecklistItemDto row) =>
            row.Id is not { } id || (!baseById.ContainsKey(id) && !currentIds.Contains(id));

        var seen = new HashSet<Guid>();
        var edit = incoming.Where(r => r.Id is not { } id || seen.Add(id)).ToList();

        if (!Moved(baseRows, edit, baseById))
        {
            // The note's order, with this edit's new rows each after the row it followed in the edit.
            return InsertAfterNeighbours(merged, edit, IsAddition, Copy);
        }

        // The edit moved rows: its order wins, and everyone else's new rows go in after the row they
        // followed in the note.
        var mergedById = merged.ToDictionary(r => r.Id!.Value);
        var inEditOrder = edit
            .Where(r => IsAddition(r) || (r.Id is { } id && mergedById.ContainsKey(id)))
            .Select(r => r.Id is { } id && mergedById.TryGetValue(id, out var m) ? m : Copy(r, r.Id))
            .ToList();
        var editIds = inEditOrder.Where(r => r.Id is not null).Select(r => r.Id!.Value).ToHashSet();
        return InsertAfterNeighbours(
            inEditOrder,
            merged,
            r => !editIds.Contains(r.Id!.Value),
            (r, _) => r);
    }

    /// <summary>
    /// <paramref name="sequence"/> with the rows of <paramref name="source"/> that
    /// <paramref name="isExtra"/> picks inserted, each straight after the row it follows in
    /// <paramref name="source"/> (at the start when nothing it follows is there).
    /// </summary>
    private static List<ChecklistItemDto> InsertAfterNeighbours(
        List<ChecklistItemDto> sequence,
        IReadOnlyList<ChecklistItemDto> source,
        Func<ChecklistItemDto, bool> isExtra,
        Func<ChecklistItemDto, Guid?, ChecklistItemDto> place)
    {
        var result = new List<ChecklistItemDto>(sequence);
        var cursor = 0;
        foreach (var row in source)
        {
            if (isExtra(row))
            {
                result.Insert(cursor++, place(row, row.Id));
                continue;
            }
            var at = row.Id is { } id ? result.FindIndex(r => r.Id == id) : -1;
            if (at >= 0) cursor = at + 1;
        }
        return result;
    }

    /// <summary>True when the rows the edit kept are not in the order they had in its base.</summary>
    private static bool Moved(
        IReadOnlyList<ChecklistItemDto> baseRows,
        IReadOnlyList<ChecklistItemDto> edit,
        IReadOnlyDictionary<Guid, ChecklistItemDto> baseById)
    {
        var kept = edit.Where(r => r.Id is { } id && baseById.ContainsKey(id)).Select(r => r.Id!.Value).ToHashSet();
        var inBaseOrder = baseRows.Where(r => r.Id is { } id && kept.Contains(id)).Select(r => r.Id!.Value).Distinct();
        var inEditOrder = edit.Where(r => r.Id is { } id && kept.Contains(id)).Select(r => r.Id!.Value);
        return !inBaseOrder.SequenceEqual(inEditOrder);
    }

    /// <summary><paramref name="now"/> with whatever the edit changed from <paramref name="before"/> to <paramref name="after"/>.</summary>
    private static ChecklistItemDto Changed(ChecklistItemDto now, ChecklistItemDto before, ChecklistItemDto after) => new()
    {
        Id = now.Id,
        Text = after.Text != before.Text ? after.Text : now.Text,
        IsChecked = after.IsChecked != before.IsChecked ? after.IsChecked : now.IsChecked,
        // An edit that doesn't say (null) leaves the indent alone.
        Indent = after.Indent is { } indent && indent != (before.Indent ?? now.Indent) ? indent : now.Indent,
    };

    private static ChecklistItemDto Copy(ChecklistItemDto row, Guid? id) => new()
    {
        Id = id,
        Text = row.Text,
        IsChecked = row.IsChecked,
        Indent = row.Indent,
    };

    /// <summary>The rows that have an id, by id; a repeated id counts once, as its first row.</summary>
    private static Dictionary<Guid, ChecklistItemDto> ById(IEnumerable<ChecklistItemDto> rows)
    {
        var byId = new Dictionary<Guid, ChecklistItemDto>();
        foreach (var row in rows)
            if (row.Id is { } id) byId.TryAdd(id, row);
        return byId;
    }
}
