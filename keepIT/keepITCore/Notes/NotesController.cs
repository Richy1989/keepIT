using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Notes.Dtos;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// CRUD for the caller's notes. Every action is scoped to the authenticated user's id
/// (<see cref="ClaimsPrincipalExtensions.GetUserId"/>) — a caller can never read or mutate another
/// user's notes. Sharing/collaboration is a later pass; for now ownership is the only access path.
/// </summary>
[ApiController]
[Authorize]
[Route("api/notes")]
public class NotesController : ControllerBase
{
    private readonly AppDbContext _db;

    /// <summary>Injects the database context.</summary>
    /// <param name="db">The EF Core context.</param>
    public NotesController(AppDbContext db) => _db = db;

    /// <summary>
    /// Lists the caller's notes (pinned first, then most-recently updated). Defaults to the active
    /// grid; pass <paramref name="archived"/> or <paramref name="trashed"/> for those views, and
    /// one or more <c>listId</c> values to filter to notes in any of those lists (union).
    /// </summary>
    /// <param name="listIds">Optional list ids to filter by (repeatable: <c>?listId=…&amp;listId=…</c>).</param>
    /// <param name="archived">When true, return archived notes instead of the active grid.</param>
    /// <param name="trashed">When true, return trashed notes (overrides <paramref name="archived"/>).</param>
    /// <returns>200 with the matching notes.</returns>
    [HttpGet]
    public async Task<ActionResult<List<NoteDto>>> GetNotes(
        [FromQuery(Name = "listId")] Guid[]? listIds,
        [FromQuery] bool archived = false,
        [FromQuery] bool trashed = false)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var query = _db.Notes.AsNoTracking().Where(n => n.OwnerId == ownerId);

        // Trash is its own view; otherwise split active vs archived.
        query = trashed
            ? query.Where(n => n.IsTrashed)
            : query.Where(n => !n.IsTrashed && n.IsArchived == archived);

        if (listIds is { Length: > 0 })
            query = query.Where(n => n.NoteLists.Any(nl => nl.UserId == ownerId && listIds.Contains(nl.ListId)));

        var notes = await query
            .Include(n => n.ChecklistItems)
            .Include(n => n.NoteLists.Where(nl => nl.UserId == ownerId))
            .OrderByDescending(n => n.IsPinned)
            .ThenByDescending(n => n.UpdatedAtUtc)
            .ToListAsync();

        return Ok(notes.Select(n => ToDto(n, ownerId.Value)).ToList());
    }

    /// <summary>Gets a single note by id.</summary>
    /// <param name="id">The note id.</param>
    /// <returns>200 with the note, or 404 if it doesn't exist or isn't the caller's.</returns>
    [HttpGet("{id:guid}")]
    public async Task<ActionResult<NoteDto>> GetNote(Guid id)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var dto = await LoadDtoAsync(id, ownerId.Value);
        return dto is null ? NotFound() : Ok(dto);
    }

    /// <summary>Creates a note for the caller.</summary>
    /// <param name="dto">The note's type, content, optional checklist items, and lists.</param>
    /// <returns>201 with the created note.</returns>
    [HttpPost]
    public async Task<ActionResult<NoteDto>> Create(CreateNoteDto dto)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var now = DateTime.UtcNow;
        var note = new Note
        {
            Id = Guid.NewGuid(),
            OwnerId = ownerId.Value,
            Type = dto.Type,
            Title = dto.Title,
            Body = dto.Body,
            Color = dto.Color,
            CreatedAtUtc = now,
            UpdatedAtUtc = now,
        };

        if (dto.ChecklistItems is not null)
        {
            var order = 0;
            foreach (var item in dto.ChecklistItems)
                note.ChecklistItems.Add(NewChecklistItem(note.Id, item, order++));
        }

        foreach (var listId in await OwnedListIdsAsync(ownerId.Value, dto.ListIds))
            note.NoteLists.Add(new NoteList { NoteId = note.Id, ListId = listId, UserId = ownerId.Value });

        _db.Notes.Add(note);
        await _db.SaveChangesAsync();

        var created = await LoadDtoAsync(note.Id, ownerId.Value);
        return CreatedAtAction(nameof(GetNote), new { id = note.Id }, created);
    }

    /// <summary>Replaces a note's editable content (title, body, color, type, checklist items).</summary>
    /// <param name="id">The note id.</param>
    /// <param name="dto">The new content. Checklist items are replaced wholesale.</param>
    /// <returns>200 with the updated note, or 404 if it isn't the caller's.</returns>
    [HttpPut("{id:guid}")]
    public async Task<ActionResult<NoteDto>> Update(Guid id, UpdateNoteDto dto)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var note = await _db.Notes
            .Where(n => n.Id == id && n.OwnerId == ownerId)
            .Include(n => n.ChecklistItems)
            .FirstOrDefaultAsync();
        if (note is null) return NotFound();

        note.Type = dto.Type;
        note.Title = dto.Title;
        note.Body = dto.Body;
        note.Color = dto.Color;
        note.UpdatedAtUtc = DateTime.UtcNow;

        // Reconcile checklist items in place (match by id): update existing rows, insert genuinely
        // new ones, delete the rest. Deleting every row and re-inserting confuses EF's change tracker
        // (it emits a stray UPDATE against an already-deleted row → DbUpdateConcurrencyException) and
        // would also churn item ids on every save.
        var incoming = dto.ChecklistItems ?? new List<ChecklistItemDto>();
        var existingById = note.ChecklistItems.ToDictionary(c => c.Id);
        var keptIds = new HashSet<Guid>();

        var order = 0;
        foreach (var item in incoming)
        {
            if (item.Id is { } itemId && existingById.TryGetValue(itemId, out var existing))
            {
                existing.Text = item.Text;
                existing.IsChecked = item.IsChecked;
                existing.Order = order;
                keptIds.Add(itemId);
            }
            else
            {
                _db.ChecklistItems.Add(NewChecklistItem(note.Id, item, order));
            }
            order++;
        }

        foreach (var stale in existingById.Values.Where(c => !keptIds.Contains(c.Id)))
            _db.ChecklistItems.Remove(stale);

        await _db.SaveChangesAsync();
        return Ok((await LoadDtoAsync(id, ownerId.Value))!);
    }

    /// <summary>Toggles the note's pin / archive / trash flags (the quick card actions).</summary>
    /// <param name="id">The note id.</param>
    /// <param name="dto">The flags to change; nulls are left as-is. Set <c>isTrashed=false</c> to restore.</param>
    /// <returns>200 with the updated note, or 404 if it isn't the caller's.</returns>
    [HttpPatch("{id:guid}/state")]
    public async Task<ActionResult<NoteDto>> SetState(Guid id, NoteStateDto dto)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var note = await _db.Notes.FirstOrDefaultAsync(n => n.Id == id && n.OwnerId == ownerId);
        if (note is null) return NotFound();

        if (dto.IsPinned is not null) note.IsPinned = dto.IsPinned.Value;
        if (dto.IsArchived is not null) note.IsArchived = dto.IsArchived.Value;
        if (dto.IsTrashed is not null) note.IsTrashed = dto.IsTrashed.Value;
        note.UpdatedAtUtc = DateTime.UtcNow;

        await _db.SaveChangesAsync();
        return Ok((await LoadDtoAsync(id, ownerId.Value))!);
    }

    /// <summary>Replaces the set of the caller's lists this note belongs to.</summary>
    /// <param name="id">The note id.</param>
    /// <param name="dto">The complete new set of list ids (only the caller's own lists are honored).</param>
    /// <returns>200 with the updated note, or 404 if it isn't the caller's.</returns>
    [HttpPut("{id:guid}/lists")]
    public async Task<ActionResult<NoteDto>> SetLists(Guid id, SetNoteListsDto dto)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var note = await _db.Notes
            .Where(n => n.Id == id && n.OwnerId == ownerId)
            .Include(n => n.NoteLists.Where(nl => nl.UserId == ownerId))
            .FirstOrDefaultAsync();
        if (note is null) return NotFound();

        var target = await OwnedListIdsAsync(ownerId.Value, dto.ListIds);
        var current = note.NoteLists.Where(nl => nl.UserId == ownerId).ToList();

        _db.NoteLists.RemoveRange(current.Where(nl => !target.Contains(nl.ListId)));
        var keep = current.Select(nl => nl.ListId).ToHashSet();
        foreach (var listId in target.Where(lid => !keep.Contains(lid)))
            _db.NoteLists.Add(new NoteList { NoteId = note.Id, ListId = listId, UserId = ownerId.Value });

        await _db.SaveChangesAsync();
        return Ok((await LoadDtoAsync(id, ownerId.Value))!);
    }

    /// <summary>Permanently deletes a note (used to purge from trash). For soft-delete, set the trash flag.</summary>
    /// <param name="id">The note id.</param>
    /// <returns>204 on success, or 404 if it isn't the caller's.</returns>
    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var note = await _db.Notes.FirstOrDefaultAsync(n => n.Id == id && n.OwnerId == ownerId);
        if (note is null) return NotFound();

        _db.Notes.Remove(note);
        await _db.SaveChangesAsync();
        return NoContent();
    }

    // ---- helpers ----

    /// <summary>Loads a note (no tracking) and projects it to a DTO, or null if not the caller's.</summary>
    /// <param name="id">The note id.</param>
    /// <param name="ownerId">The caller's id, used to scope the query and the per-user list memberships.</param>
    /// <returns>The note DTO, or null if it doesn't exist or isn't owned by the caller.</returns>
    private async Task<NoteDto?> LoadDtoAsync(Guid id, Guid ownerId)
    {
        var note = await _db.Notes.AsNoTracking()
            .Where(n => n.Id == id && n.OwnerId == ownerId)
            .Include(n => n.ChecklistItems)
            .Include(n => n.NoteLists.Where(nl => nl.UserId == ownerId))
            .FirstOrDefaultAsync();

        return note is null ? null : ToDto(note, ownerId);
    }

    /// <summary>Filters the requested list ids down to those the caller actually owns.</summary>
    /// <param name="ownerId">The caller's id.</param>
    /// <param name="requested">The requested list ids (may be null/empty).</param>
    /// <returns>The subset of ids that are the caller's own lists.</returns>
    private async Task<List<Guid>> OwnedListIdsAsync(Guid ownerId, List<Guid>? requested)
    {
        if (requested is not { Count: > 0 }) return new List<Guid>();
        return await _db.Lists
            .Where(l => l.OwnerId == ownerId && requested.Contains(l.Id))
            .Select(l => l.Id)
            .ToListAsync();
    }

    /// <summary>Maps a checklist DTO to a fresh entity (a new id is always assigned).</summary>
    /// <param name="noteId">The owning note id.</param>
    /// <param name="dto">The checklist row.</param>
    /// <param name="order">The row's sort position, assigned by the caller.</param>
    /// <returns>A new <see cref="ChecklistItem"/>.</returns>
    private static ChecklistItem NewChecklistItem(Guid noteId, ChecklistItemDto dto, int order) => new()
    {
        Id = Guid.NewGuid(),
        NoteId = noteId,
        Text = dto.Text,
        IsChecked = dto.IsChecked,
        Order = order,
    };

    /// <summary>Projects a note entity to its client DTO, including the caller's list memberships.</summary>
    /// <param name="n">The note entity (with checklist items and note-lists loaded).</param>
    /// <param name="ownerId">The caller's id, used to select per-user list memberships.</param>
    /// <returns>The note DTO.</returns>
    private static NoteDto ToDto(Note n, Guid ownerId) => new()
    {
        Id = n.Id,
        Type = n.Type,
        Title = n.Title,
        Body = n.Body,
        Color = n.Color,
        IsPinned = n.IsPinned,
        IsArchived = n.IsArchived,
        IsTrashed = n.IsTrashed,
        CreatedAtUtc = n.CreatedAtUtc,
        UpdatedAtUtc = n.UpdatedAtUtc,
        ChecklistItems = n.ChecklistItems
            .OrderBy(c => c.Order)
            .Select(c => new ChecklistItemDto { Id = c.Id, Text = c.Text, IsChecked = c.IsChecked, Order = c.Order })
            .ToList(),
        ListIds = n.NoteLists.Where(nl => nl.UserId == ownerId).Select(nl => nl.ListId).ToList(),
    };
}
