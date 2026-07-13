using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Notes.Dtos;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// CRUD for a caller's notes. Access is "own OR shared" — every action resolves the caller's
/// <see cref="NoteAccess"/> through <see cref="NoteAccessService"/> (never a bare <c>OwnerId ==</c>
/// filter), so a collaborator can reach a shared note but no one can touch a note they neither own
/// nor hold a share on. Content mutations require Editor access; pin/archive/trash and list
/// membership are per-user and only need read access.
/// </summary>
[ApiController]
[Authorize]
[Route("api/notes")]
// Worst legitimate payload (100k body or 500×2k checklist chars + JSON overhead) stays well under
// 2 MB; anything bigger is abuse, so reject it before model binding instead of at Kestrel's ~28 MB.
[RequestSizeLimit(2 * 1024 * 1024)]
public class NotesController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IRealtimeNotifier _notifier;
    private readonly NoteAccessService _access;

    /// <summary>Injects the database context, the realtime notifier, and the access resolver.</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="notifier">Pushes change signals to affected users' devices.</param>
    /// <param name="access">Resolves "own OR shared" access and the realtime recipient set.</param>
    public NotesController(AppDbContext db, IRealtimeNotifier notifier, NoteAccessService access)
    {
        _db = db;
        _notifier = notifier;
        _access = access;
    }

    /// <summary>
    /// Lists the notes in the caller's grid (owned or shared with them), pinned first then most-
    /// recently updated. The pin/archive/trash view is the caller's own; pass <paramref name="archived"/>
    /// or <paramref name="trashed"/> for those views, and one or more <c>listId</c> values to filter
    /// to notes in any of the caller's matching lists (union).
    /// </summary>
    /// <param name="listIds">Optional list ids to filter by (repeatable: <c>?listId=…&amp;listId=…</c>).</param>
    /// <param name="archived">When true, return the caller's archived notes instead of the active grid.</param>
    /// <param name="trashed">When true, return the caller's trashed notes (overrides <paramref name="archived"/>).</param>
    /// <param name="reminders">When true, return the caller's notes with a reminder set (active and
    /// archived, never trashed), soonest first (overrides the other view flags).</param>
    /// <returns>200 with the matching notes.</returns>
    [HttpGet]
    public async Task<ActionResult<List<NoteDto>>> GetNotes(
        [FromQuery(Name = "listId")] Guid[]? listIds,
        [FromQuery] bool archived = false,
        [FromQuery] bool trashed = false,
        [FromQuery] bool reminders = false)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        // The grid is driven off the caller's per-user state rows: one exists for every note in
        // their grid (owned or accepted-share), and it carries their private pin/archive/trash view.
        var query = _db.NoteUserStates.AsNoTracking().Where(us => us.UserId == callerId);

        query = reminders
            // The reminders view spans active *and* archived (like Keep), but never trash.
            ? query.Where(us => !us.IsTrashed && us.Note.Reminders.Any(r => r.UserId == us.UserId))
            : trashed
                ? query.Where(us => us.IsTrashed)
                : query.Where(us => !us.IsTrashed && us.IsArchived == archived);

        if (listIds is { Length: > 0 })
            query = query.Where(us => us.Note.NoteLists.Any(nl => nl.UserId == callerId && listIds.Contains(nl.ListId)));

        var states = await query
            .Include(us => us.Note).ThenInclude(n => n.ChecklistItems)
            .Include(us => us.Note).ThenInclude(n => n.NoteLists.Where(nl => nl.UserId == callerId))
            .Include(us => us.Note).ThenInclude(n => n.NoteShares)
            .Include(us => us.Note).ThenInclude(n => n.Reminders.Where(r => r.UserId == callerId))
            .OrderByDescending(us => us.IsPinned)
            .ThenByDescending(us => us.Note.UpdatedAtUtc)
            .ToListAsync();

        // The reminders view sorts by what's due next, ignoring pins (small result set, in-memory).
        if (reminders)
            states = states
                .OrderBy(us => us.Note.Reminders.FirstOrDefault()?.RemindAtUtc ?? DateTime.MaxValue)
                .ToList();

        return Ok(states.Select(us => ToDto(us.Note, us, callerId.Value)).ToList());
    }

    /// <summary>Gets a single note by id (if the caller owns it or has a share on it).</summary>
    /// <param name="id">The note id.</param>
    /// <returns>200 with the note, or 404 if it doesn't exist or the caller has no access.</returns>
    [HttpGet("{id:guid}")]
    public async Task<ActionResult<NoteDto>> GetNote(Guid id)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var dto = await LoadDtoAsync(id, callerId.Value);
        return dto is null ? NotFound() : Ok(dto);
    }

    /// <summary>Creates a note for the caller (its owner).</summary>
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

        // The owner's private view row — its existence puts the note in their grid.
        note.UserStates.Add(new NoteUserState { NoteId = note.Id, UserId = ownerId.Value });

        _db.Notes.Add(note);
        await _db.SaveChangesAsync();
        await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Notes, RealtimeResources.Lists);

        var created = await LoadDtoAsync(note.Id, ownerId.Value);
        return CreatedAtAction(nameof(GetNote), new { id = note.Id }, created);
    }

    /// <summary>Replaces a note's editable content (title, body, color, type, checklist items).</summary>
    /// <param name="id">The note id.</param>
    /// <param name="dto">The new content. Checklist items are replaced wholesale.</param>
    /// <returns>200 with the updated note, 403 if the caller is a viewer, or 404 if they have no access.</returns>
    [HttpPut("{id:guid}")]
    public async Task<ActionResult<NoteDto>> Update(Guid id, UpdateNoteDto dto)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(id, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.CanEdit) return Forbid();

        var note = await _db.Notes
            .Where(n => n.Id == id)
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
        // Content is shared: fan the change out to the owner and every collaborator.
        await NotifyRecipientsAsync(id, RealtimeResources.Notes);
        return Ok((await LoadDtoAsync(id, callerId.Value))!);
    }

    /// <summary>
    /// Toggles the caller's <em>private</em> pin / archive / trash view of a note. On a shared note
    /// this changes only the caller's own grid, never other collaborators' or the owner's.
    /// </summary>
    /// <param name="id">The note id.</param>
    /// <param name="dto">The flags to change; nulls are left as-is. Set <c>isTrashed=false</c> to restore.</param>
    /// <returns>200 with the updated note, or 404 if the caller has no access.</returns>
    [HttpPatch("{id:guid}/state")]
    public async Task<ActionResult<NoteDto>> SetState(Guid id, NoteStateDto dto)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        // Read access is enough — pinning/archiving/trashing your own view isn't a content mutation.
        var access = await _access.ResolveAsync(id, callerId.Value);
        if (access is null) return NotFound();

        var state = await _db.NoteUserStates.FirstOrDefaultAsync(us => us.NoteId == id && us.UserId == callerId);
        if (state is null)
        {
            state = new NoteUserState { NoteId = id, UserId = callerId.Value };
            _db.NoteUserStates.Add(state);
        }

        if (dto.IsPinned is not null) state.IsPinned = dto.IsPinned.Value;
        if (dto.IsArchived is not null) state.IsArchived = dto.IsArchived.Value;
        if (dto.IsTrashed is not null) state.IsTrashed = dto.IsTrashed.Value;

        await _db.SaveChangesAsync();
        // Per-user view state: only the caller's own devices need to resync.
        await _notifier.NotifyAsync(callerId.Value, RealtimeResources.Notes, RealtimeResources.Lists);
        return Ok((await LoadDtoAsync(id, callerId.Value))!);
    }

    /// <summary>
    /// Sets (or replaces) the caller's <em>private</em> reminder on a note. Like pin/archive/trash
    /// this is per-user — read access suffices (viewers set their own), and no other collaborator
    /// sees it. Rescheduling always resets the fired state.
    /// </summary>
    /// <param name="id">The note id.</param>
    /// <param name="dto">When to remind (UTC) and how often to repeat.</param>
    /// <returns>200 with the updated note, or 404 if the caller has no access.</returns>
    [HttpPut("{id:guid}/reminder")]
    public async Task<ActionResult<NoteDto>> SetReminder(Guid id, SetNoteReminderDto dto)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(id, callerId.Value);
        if (access is null) return NotFound();

        var reminder = await _db.NoteReminders.FirstOrDefaultAsync(r => r.NoteId == id && r.UserId == callerId);
        if (reminder is null)
        {
            reminder = new NoteReminder { NoteId = id, UserId = callerId.Value };
            _db.NoteReminders.Add(reminder);
        }

        // JSON binding can yield a Local or Unspecified Kind; Npgsql requires Utc for timestamptz.
        reminder.RemindAtUtc = dto.RemindAtUtc.Kind switch
        {
            DateTimeKind.Utc => dto.RemindAtUtc,
            DateTimeKind.Local => dto.RemindAtUtc.ToUniversalTime(),
            _ => DateTime.SpecifyKind(dto.RemindAtUtc, DateTimeKind.Utc),
        };
        reminder.Recurrence = dto.Recurrence;
        reminder.FiredAtUtc = null;

        await _db.SaveChangesAsync();
        // Per-user state: only the caller's own devices need to resync.
        await _notifier.NotifyAsync(callerId.Value, RealtimeResources.Notes);
        return Ok((await LoadDtoAsync(id, callerId.Value))!);
    }

    /// <summary>Clears the caller's reminder on a note (idempotent — 200 even if none was set).</summary>
    /// <param name="id">The note id.</param>
    /// <returns>200 with the updated note, or 404 if the caller has no access.</returns>
    [HttpDelete("{id:guid}/reminder")]
    public async Task<ActionResult<NoteDto>> ClearReminder(Guid id)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(id, callerId.Value);
        if (access is null) return NotFound();

        var reminder = await _db.NoteReminders.FirstOrDefaultAsync(r => r.NoteId == id && r.UserId == callerId);
        if (reminder is not null)
        {
            _db.NoteReminders.Remove(reminder);
            await _db.SaveChangesAsync();
            await _notifier.NotifyAsync(callerId.Value, RealtimeResources.Notes);
        }

        return Ok((await LoadDtoAsync(id, callerId.Value))!);
    }

    /// <summary>Replaces the set of the caller's lists this note belongs to (private to the caller).</summary>
    /// <param name="id">The note id.</param>
    /// <param name="dto">The complete new set of list ids (only the caller's own lists are honored).</param>
    /// <returns>200 with the updated note, or 404 if the caller has no access.</returns>
    [HttpPut("{id:guid}/lists")]
    public async Task<ActionResult<NoteDto>> SetLists(Guid id, SetNoteListsDto dto)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(id, callerId.Value);
        if (access is null) return NotFound();

        var current = await _db.NoteLists
            .Where(nl => nl.NoteId == id && nl.UserId == callerId)
            .ToListAsync();

        var target = await OwnedListIdsAsync(callerId.Value, dto.ListIds);

        _db.NoteLists.RemoveRange(current.Where(nl => !target.Contains(nl.ListId)));
        var keep = current.Select(nl => nl.ListId).ToHashSet();
        foreach (var listId in target.Where(lid => !keep.Contains(lid)))
            _db.NoteLists.Add(new NoteList { NoteId = id, ListId = listId, UserId = callerId.Value });

        await _db.SaveChangesAsync();
        // List membership is private to the caller — only their devices resync.
        await _notifier.NotifyAsync(callerId.Value, RealtimeResources.Notes, RealtimeResources.Lists);
        return Ok((await LoadDtoAsync(id, callerId.Value))!);
    }

    /// <summary>
    /// Permanently deletes a note (purge from trash). Owner-only — cascades its shares, per-user
    /// state, list memberships, and checklist, so it vanishes from every collaborator's grid too.
    /// A collaborator wanting rid of a shared note should leave the share, not delete the note.
    /// </summary>
    /// <param name="id">The note id.</param>
    /// <returns>204 on success, 403 if the caller isn't the owner, or 404 if they have no access.</returns>
    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(id, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.IsOwner) return Forbid();

        // Capture recipients before the delete so collaborators also get told to resync.
        var recipients = await _access.RecipientIdsAsync(id);

        var note = await _db.Notes.FirstOrDefaultAsync(n => n.Id == id);
        if (note is null) return NotFound();

        _db.Notes.Remove(note);
        await _db.SaveChangesAsync();
        await Task.WhenAll(recipients.Select(uid =>
            _notifier.NotifyAsync(uid, RealtimeResources.Notes, RealtimeResources.Lists)));
        return NoContent();
    }

    // ---- helpers ----

    /// <summary>Notifies the owner and every collaborator of a note that the given resources changed.</summary>
    /// <param name="noteId">The note whose recipients to reach.</param>
    /// <param name="resources">The affected resource names (see <see cref="RealtimeResources"/>).</param>
    private async Task NotifyRecipientsAsync(Guid noteId, params string[] resources)
    {
        var recipients = await _access.RecipientIdsAsync(noteId);
        await Task.WhenAll(recipients.Select(uid => _notifier.NotifyAsync(uid, resources)));
    }

    /// <summary>Loads a note (no tracking) and projects it for a caller, or null if they have no access.</summary>
    /// <param name="id">The note id.</param>
    /// <param name="callerId">The caller, used to scope list memberships, per-user state, and access.</param>
    /// <returns>The note DTO, or null if it doesn't exist or the caller neither owns nor has a share on it.</returns>
    private async Task<NoteDto?> LoadDtoAsync(Guid id, Guid callerId)
    {
        var note = await _db.Notes.AsNoTracking()
            .Where(n => n.Id == id)
            .Include(n => n.ChecklistItems)
            .Include(n => n.NoteLists.Where(nl => nl.UserId == callerId))
            .Include(n => n.NoteShares)
            .Include(n => n.Reminders.Where(r => r.UserId == callerId))
            .FirstOrDefaultAsync();

        if (note is null) return null;
        if (note.OwnerId != callerId && note.NoteShares.All(s => s.GranteeId != callerId)) return null;

        var state = await _db.NoteUserStates.AsNoTracking()
            .FirstOrDefaultAsync(us => us.NoteId == id && us.UserId == callerId);

        return ToDto(note, state, callerId);
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

    /// <summary>Projects a note entity to its client DTO for a given caller (view state + access resolved).</summary>
    /// <param name="n">The note entity (with checklist items, the caller's note-lists, and shares loaded).</param>
    /// <param name="state">The caller's per-user view state, or null (treated as all-false defaults).</param>
    /// <param name="callerId">The caller, used to resolve their view, list memberships, and role.</param>
    /// <returns>The note DTO.</returns>
    private static NoteDto ToDto(Note n, NoteUserState? state, Guid callerId)
    {
        var isOwner = n.OwnerId == callerId;
        var role = isOwner ? (NoteRole?)null : n.NoteShares.FirstOrDefault(s => s.GranteeId == callerId)?.Role;
        var reminder = n.Reminders.FirstOrDefault(r => r.UserId == callerId);

        return new NoteDto
        {
            Id = n.Id,
            Type = n.Type,
            Title = n.Title,
            Body = n.Body,
            Color = n.Color,
            IsPinned = state?.IsPinned ?? false,
            IsArchived = state?.IsArchived ?? false,
            IsTrashed = state?.IsTrashed ?? false,
            RemindAtUtc = reminder?.RemindAtUtc,
            ReminderRecurrence = reminder?.Recurrence,
            ReminderFired = reminder?.FiredAtUtc is not null,
            CreatedAtUtc = n.CreatedAtUtc,
            UpdatedAtUtc = n.UpdatedAtUtc,
            IsOwner = isOwner,
            Role = role,
            CanEdit = isOwner || role == NoteRole.Editor,
            IsShared = isOwner && n.NoteShares.Count > 0,
            ChecklistItems = n.ChecklistItems
                .OrderBy(c => c.Order)
                .Select(c => new ChecklistItemDto { Id = c.Id, Text = c.Text, IsChecked = c.IsChecked, Order = c.Order })
                .ToList(),
            ListIds = n.NoteLists.Where(nl => nl.UserId == callerId).Select(nl => nl.ListId).ToList(),
        };
    }
}
