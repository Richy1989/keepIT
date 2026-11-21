using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Lists.Dtos;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Lists;

/// <summary>
/// CRUD for the caller's lists. Lists are private per user (ARCHITECTURE.md) — every action is
/// scoped to the authenticated user's id. Deleting a list drops its note memberships but never the
/// notes themselves.
/// </summary>
[ApiController]
[Authorize]
[Route("api/lists")]
public class ListsController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IRealtimeNotifier _notifier;

    /// <summary>Injects the database context and the realtime change notifier.</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="notifier">Pushes change signals to the caller's other devices.</param>
    public ListsController(AppDbContext db, IRealtimeNotifier notifier)
    {
        _db = db;
        _notifier = notifier;
    }

    /// <summary>Lists the caller's lists (alphabetical), each with its active-note count.</summary>
    /// <returns>200 with the caller's lists.</returns>
    [HttpGet]
    public async Task<ActionResult<List<ListDto>>> GetLists()
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var lists = await Project(_db.Lists.Where(l => l.OwnerId == ownerId).OrderBy(l => l.Name), ownerId.Value)
            .ToListAsync();

        return Ok(lists);
    }

    /// <summary>Lists as the caller sees them, each with its active-note count.</summary>
    /// <param name="lists">The caller's lists to project.</param>
    /// <param name="ownerId">The caller.</param>
    /// <returns>The projection, still a query.</returns>
    private static IQueryable<ListDto> Project(IQueryable<KeepList> lists, Guid ownerId) =>
        lists.AsNoTracking().Select(l => new ListDto
        {
            Id = l.Id,
            Name = l.Name,
            Color = l.Color,
            Icon = l.Icon,
            CreatedAtUtc = l.CreatedAtUtc,
            // Pin/archive/trash are per-user: count only what the caller actually sees when they
            // click this list — i.e. the active view, which excludes both archived and trashed.
            NoteCount = l.NoteLists.Count(nl =>
                nl.UserId == ownerId
                && !nl.Note.UserStates.Any(us => us.UserId == ownerId && (us.IsTrashed || us.IsArchived))),
        });

    /// <summary>The caller's own list <paramref name="id"/>, or null when it isn't theirs or doesn't exist.</summary>
    /// <param name="id">The list id.</param>
    /// <param name="ownerId">The caller.</param>
    /// <returns>The list, or null.</returns>
    private Task<ListDto?> LoadOwnAsync(Guid id, Guid ownerId) =>
        Project(_db.Lists.Where(l => l.Id == id && l.OwnerId == ownerId), ownerId).FirstOrDefaultAsync();

    /// <summary>
    /// Creates a list for the caller. A create that names an id the caller's own list already has
    /// is the same create sent again, its answer having been lost: it is answered with that list and
    /// changes nothing (see <see cref="CreateListDto.Id"/>).
    /// </summary>
    /// <param name="dto">The list name, and optional color and icon.</param>
    /// <returns>201 with the created list, or 400 when the icon isn't a single symbol.</returns>
    [HttpPost]
    public async Task<ActionResult<ListDto>> Create(CreateListDto dto)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var icon = dto.Icon?.Trim();
        if (!string.IsNullOrEmpty(icon) && !ListIcon.IsValid(icon))
        {
            ModelState.AddModelError(nameof(dto.Icon), ListIcon.Rule);
            return ValidationProblem(ModelState);
        }

        var wanted = dto.Id is { } picked && picked != Guid.Empty ? picked : (Guid?)null;
        if (wanted is { } resent && await LoadOwnAsync(resent, ownerId.Value) is { } made)
            return CreatedAtAction(nameof(GetLists), new { id = resent }, made);

        var list = new KeepList
        {
            // An id someone else's list has is not the caller's to reuse: the list gets its own.
            Id = wanted is { } free && !await _db.Lists.AnyAsync(l => l.Id == free) ? free : Guid.NewGuid(),
            OwnerId = ownerId.Value,
            Name = dto.Name.Trim(),
            Color = dto.Color,
            Icon = string.IsNullOrEmpty(icon) ? null : icon,
            CreatedAtUtc = DateTime.UtcNow,
        };

        _db.Lists.Add(list);
        try
        {
            await _db.SaveChangesAsync();
        }
        catch (DbUpdateException) when (wanted == list.Id)
        {
            // The same create, sent again while the first was still being saved, lost the race for
            // the id: the first one's list is the answer.
            _db.ChangeTracker.Clear();
            if (await LoadOwnAsync(list.Id, ownerId.Value) is not { } first) throw;
            return CreatedAtAction(nameof(GetLists), new { id = list.Id }, first);
        }
        await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Lists);

        return CreatedAtAction(nameof(GetLists), new { id = list.Id }, ToDto(list, 0));
    }

    /// <summary>Renames, recolors and/or re-icons a list.</summary>
    /// <param name="id">The list id.</param>
    /// <param name="dto">The fields to change; nulls are left unchanged, and an empty icon removes it.</param>
    /// <returns>
    /// 200 with the updated list, 400 when the icon isn't a single symbol, or 404 if it isn't the
    /// caller's.
    /// </returns>
    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<ListDto>> Update(Guid id, UpdateListDto dto)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        // Null leaves the icon alone, so "" is how a client removes it.
        var icon = dto.Icon?.Trim();
        if (!string.IsNullOrEmpty(icon) && !ListIcon.IsValid(icon))
        {
            ModelState.AddModelError(nameof(dto.Icon), ListIcon.Rule);
            return ValidationProblem(ModelState);
        }

        var list = await _db.Lists.FirstOrDefaultAsync(l => l.Id == id && l.OwnerId == ownerId);
        if (list is null) return NotFound();

        if (dto.Name is not null) list.Name = dto.Name.Trim();
        if (dto.Color is not null) list.Color = dto.Color;
        if (icon is not null) list.Icon = icon.Length == 0 ? null : icon;

        await _db.SaveChangesAsync();
        await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Lists);

        var count = await _db.NoteLists.CountAsync(nl =>
            nl.ListId == id && nl.UserId == ownerId
            && !nl.Note.UserStates.Any(us => us.UserId == ownerId && (us.IsTrashed || us.IsArchived)));
        return Ok(ToDto(list, count));
    }

    /// <summary>Deletes a list. Its note memberships are removed (cascade); the notes survive.</summary>
    /// <param name="id">The list id.</param>
    /// <returns>204 on success, or 404 if it isn't the caller's.</returns>
    [HttpDelete("{id:guid}")]
    public async Task<IActionResult> Delete(Guid id)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var list = await _db.Lists.FirstOrDefaultAsync(l => l.Id == id && l.OwnerId == ownerId);
        if (list is null) return NotFound();

        _db.Lists.Remove(list);
        await _db.SaveChangesAsync();
        // Deleting a list drops its note memberships, so notes' listIds change too.
        await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Lists, RealtimeResources.Notes);
        return NoContent();
    }

    /// <summary>Projects a list entity to its DTO with a supplied note count.</summary>
    /// <param name="l">The list entity.</param>
    /// <param name="noteCount">The caller's active-note count for this list.</param>
    /// <returns>The list DTO.</returns>
    private static ListDto ToDto(KeepList l, int noteCount) => new()
    {
        Id = l.Id,
        Name = l.Name,
        Color = l.Color,
        Icon = l.Icon,
        NoteCount = noteCount,
        CreatedAtUtc = l.CreatedAtUtc,
    };
}
