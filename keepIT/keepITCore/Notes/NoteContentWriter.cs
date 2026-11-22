using keepITCore.Data;
using keepITCore.Notes.Dtos;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>What <see cref="NoteContentWriter.UpdateAsync"/> did with an update.</summary>
public enum NoteContentWriteResult
{
    /// <summary>The update was written (or named no fields, so there was nothing to write).</summary>
    Saved,

    /// <summary>The note doesn't exist (any more).</summary>
    NotFound,

    /// <summary>The merged checklist would hold more than <see cref="NoteLimits.ChecklistItems"/> rows.</summary>
    ChecklistTooLong,
}

/// <summary>
/// The one write path for a note's content: title, body, colour, background, type and checklist.
/// Shared by <see cref="NotesController"/> (a signed-in owner or Editor) and
/// <see cref="PublicNotesController"/> (anyone holding an editable public link), so both get the
/// same limits, the same checklist merge and the same retry, and neither can drift from the other.
/// It decides nothing about access: the caller has already done that.
/// </summary>
public sealed class NoteContentWriter(AppDbContext db)
{
    /// <summary>
    /// Writes <paramref name="dto"/> to the note: all of its fields, or only those named in
    /// <see cref="UpdateNoteDto.Fields"/>. Naming none changes nothing and writes nothing.
    /// </summary>
    /// <param name="id">The note.</param>
    /// <param name="dto">The new content.</param>
    /// <returns>What happened; the caller turns it into a response and tells whoever needs telling.</returns>
    public async Task<NoteContentWriteResult> UpdateAsync(Guid id, UpdateNoteDto dto)
    {
        if (dto.Fields is { Count: 0 }) return NoteContentWriteResult.Saved;

        // Two people saving the same checklist at the same moment can each delete a row the other is
        // writing, which EF reports as a concurrency failure. The merge is made to be run again on
        // the note as it then is, so it is, rather than failing the save.
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                return await ApplyUpdateAsync(id, dto);
            }
            catch (DbUpdateConcurrencyException) when (attempt < 3)
            {
                db.ChangeTracker.Clear();
            }
        }
    }

    /// <summary>Writes <paramref name="dto"/> to the note and saves it.</summary>
    private async Task<NoteContentWriteResult> ApplyUpdateAsync(Guid id, UpdateNoteDto dto)
    {
        var note = await db.Notes
            .Where(n => n.Id == id)
            .Include(n => n.ChecklistItems)
            .FirstOrDefaultAsync();
        if (note is null) return NoteContentWriteResult.NotFound;

        // Background is the exception: an update without fields leaves it alone, since an app that
        // predates it never sends one (see UpdateNoteDto.Fields).
        bool Sets(NoteField field) =>
            dto.Fields is null ? field != NoteField.Background : dto.Fields.Contains(field);

        // A switch between text and checklist hands over the content whole (the apps empty the side
        // they leave), so there is nothing to merge: the list becomes what was sent.
        var switchesType = Sets(NoteField.Type) && dto.Type != note.Type;

        if (Sets(NoteField.Type)) note.Type = dto.Type;
        if (Sets(NoteField.Title)) note.Title = dto.Title;
        if (Sets(NoteField.Body)) note.Body = dto.Body;
        if (Sets(NoteField.Color)) note.Color = dto.Color;
        if (Sets(NoteField.Background)) note.Background = dto.Background;
        note.UpdatedAtUtc = DateTime.UtcNow;

        if (Sets(NoteField.ChecklistItems))
        {
            var rows = dto.ChecklistItems ?? new List<ChecklistItemDto>();
            if (dto.ChecklistBase is not null && !switchesType)
            {
                rows = ChecklistMerge.Merge(dto.ChecklistBase, rows, CurrentRows(note));
                // Each side was held to the limit, but together they can pass it.
                if (rows.Count > NoteLimits.ChecklistItems)
                    return NoteContentWriteResult.ChecklistTooLong;
            }
            await ReconcileChecklistAsync(note, rows);
        }

        await db.SaveChangesAsync();
        return NoteContentWriteResult.Saved;
    }

    /// <summary>The note's rows as DTOs, in order: what <see cref="ChecklistMerge"/> merges into.</summary>
    private static List<ChecklistItemDto> CurrentRows(Note note) => note.ChecklistItems
        .OrderBy(c => c.Order)
        .Select(c => new ChecklistItemDto { Id = c.Id, Text = c.Text, IsChecked = c.IsChecked, Indent = c.Indent, Order = c.Order })
        .ToList();

    /// <summary>
    /// Makes <paramref name="note"/>'s checklist the <paramref name="items"/> given, in place (matched
    /// by id): existing rows updated, genuinely new ones inserted, the rest deleted. Deleting every
    /// row and re-inserting confuses EF's change tracker (it emits a stray UPDATE against an
    /// already-deleted row → DbUpdateConcurrencyException) and would also churn item ids on every save.
    /// A new row keeps the id it was sent with when no other row has it (see <see cref="FreeItemIdsAsync"/>).
    /// The merged result is then normalised through <see cref="ChecklistHierarchy"/>.
    /// </summary>
    private async Task ReconcileChecklistAsync(Note note, List<ChecklistItemDto> items)
    {
        var existingById = note.ChecklistItems.ToDictionary(c => c.Id);
        var keptIds = new HashSet<Guid>();
        var freeIds = await FreeItemIdsAsync(items.Where(i => i.Id is not { } id || !existingById.ContainsKey(id)));

        var order = 0;
        var ordered = new List<ChecklistItem>(items.Count);
        foreach (var item in items)
        {
            if (item.Id is { } itemId && existingById.TryGetValue(itemId, out var existing))
            {
                // A row sent twice is one row, as it always was: the first place it appears.
                if (!keptIds.Add(itemId)) continue;
                existing.Text = item.Text;
                existing.IsChecked = item.IsChecked;
                // An app from before sub-items sends no indent: the row keeps the one it has.
                existing.Indent = item.Indent ?? existing.Indent;
                existing.Order = order;
                ordered.Add(existing);
            }
            else
            {
                var added = NewChecklistItem(note.Id, item, order, freeIds);
                db.ChecklistItems.Add(added);
                ordered.Add(added);
            }
            order++;
        }

        // Applied to the result of the merge, so rows that only moved are normalised exactly like
        // new ones (an item that was a child and is now first becomes top-level, and so on).
        ChecklistHierarchy.Normalize(ordered);

        foreach (var stale in existingById.Values.Where(c => !keptIds.Contains(c.Id)))
            db.ChecklistItems.Remove(stale);
    }

    /// <summary>
    /// The ids among <paramref name="newRows"/> that a new row may keep: set, not empty, and held by
    /// no row of any note. The apps give a new row its id so that sending the same save twice adds it
    /// once; an id already in use — another note's row, or one sent twice — gets a fresh one instead,
    /// so nobody can reach a row by naming its id. Taken from the set as each row claims it.
    /// </summary>
    public async Task<HashSet<Guid>> FreeItemIdsAsync(IEnumerable<ChecklistItemDto> newRows)
    {
        var wanted = newRows.Where(r => r.Id is { } id && id != Guid.Empty).Select(r => r.Id!.Value).ToHashSet();
        if (wanted.Count == 0) return wanted;
        var taken = await db.ChecklistItems.Where(c => wanted.Contains(c.Id)).Select(c => c.Id).ToListAsync();
        wanted.ExceptWith(taken);
        return wanted;
    }

    /// <summary>
    /// Maps a checklist DTO to a fresh entity, under the id it was sent with when that is in
    /// <paramref name="freeIds"/> (which it is then taken out of), else a new one.
    /// </summary>
    /// <param name="noteId">The owning note id.</param>
    /// <param name="dto">The checklist row.</param>
    /// <param name="order">The row's sort position, assigned by the caller.</param>
    /// <param name="freeIds">The ids a new row may keep (see <see cref="FreeItemIdsAsync"/>).</param>
    /// <returns>A new <see cref="ChecklistItem"/>.</returns>
    public static ChecklistItem NewChecklistItem(Guid noteId, ChecklistItemDto dto, int order, HashSet<Guid> freeIds) => new()
    {
        Id = dto.Id is { } id && freeIds.Remove(id) ? id : Guid.NewGuid(),
        NoteId = noteId,
        Text = dto.Text,
        Indent = dto.Indent ?? 0,
        IsChecked = dto.IsChecked,
        Order = order,
    };
}
