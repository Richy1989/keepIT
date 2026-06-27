using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Lists.Dtos;
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

    /// <summary>Injects the database context.</summary>
    /// <param name="db">The EF Core context.</param>
    public ListsController(AppDbContext db) => _db = db;

    /// <summary>Lists the caller's lists (alphabetical), each with its active-note count.</summary>
    /// <returns>200 with the caller's lists.</returns>
    [HttpGet]
    public async Task<ActionResult<List<ListDto>>> GetLists()
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var lists = await _db.Lists.AsNoTracking()
            .Where(l => l.OwnerId == ownerId)
            .OrderBy(l => l.Name)
            .Select(l => new ListDto
            {
                Id = l.Id,
                Name = l.Name,
                Color = l.Color,
                CreatedAtUtc = l.CreatedAtUtc,
                NoteCount = l.NoteLists.Count(nl => nl.UserId == ownerId && !nl.Note.IsTrashed),
            })
            .ToListAsync();

        return Ok(lists);
    }

    /// <summary>Creates a list for the caller.</summary>
    /// <param name="dto">The list name and optional color.</param>
    /// <returns>201 with the created list.</returns>
    [HttpPost]
    public async Task<ActionResult<ListDto>> Create(CreateListDto dto)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var list = new KeepList
        {
            Id = Guid.NewGuid(),
            OwnerId = ownerId.Value,
            Name = dto.Name.Trim(),
            Color = dto.Color,
            CreatedAtUtc = DateTime.UtcNow,
        };

        _db.Lists.Add(list);
        await _db.SaveChangesAsync();

        return CreatedAtAction(nameof(GetLists), new { id = list.Id }, ToDto(list, 0));
    }

    /// <summary>Renames and/or recolors a list.</summary>
    /// <param name="id">The list id.</param>
    /// <param name="dto">The fields to change; nulls are left unchanged.</param>
    /// <returns>200 with the updated list, or 404 if it isn't the caller's.</returns>
    [HttpPatch("{id:guid}")]
    public async Task<ActionResult<ListDto>> Update(Guid id, UpdateListDto dto)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        var list = await _db.Lists.FirstOrDefaultAsync(l => l.Id == id && l.OwnerId == ownerId);
        if (list is null) return NotFound();

        if (dto.Name is not null) list.Name = dto.Name.Trim();
        if (dto.Color is not null) list.Color = dto.Color;

        await _db.SaveChangesAsync();

        var count = await _db.NoteLists.CountAsync(nl => nl.ListId == id && nl.UserId == ownerId && !nl.Note.IsTrashed);
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
        NoteCount = noteCount,
        CreatedAtUtc = l.CreatedAtUtc,
    };
}
