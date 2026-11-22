using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Notes.Dtos;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// Publishes a note to the internet, changes how, or takes it down (ARCHITECTURE.md "Public
/// links"). Owner-only, like inviting collaborators: an Editor can't widen who sees someone
/// else's note. A collaborator gets 403, anyone else the usual 404.
/// </summary>
[ApiController]
[Authorize]
[Route("api/notes/{noteId:guid}/public-link")]
public class NotePublicLinksController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly NoteAccessService _access;
    private readonly PublicLinkService _links;
    private readonly IRealtimeNotifier _notifier;
    private readonly string? _publicBaseUrl;

    /// <summary>Injects the context, access resolver, link secrets, notifier and configuration.</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="access">Resolves the caller's access to the note.</param>
    /// <param name="links">Tokens, passwords and tickets.</param>
    /// <param name="notifier">Tells the owner's devices the note's published state changed.</param>
    /// <param name="configuration">Read for <c>App:PublicBaseUrl</c>, the only base a link is built on.</param>
    public NotePublicLinksController(
        AppDbContext db,
        NoteAccessService access,
        PublicLinkService links,
        IRealtimeNotifier notifier,
        IConfiguration configuration)
    {
        _db = db;
        _access = access;
        _links = links;
        _notifier = notifier;
        _publicBaseUrl = PublicBaseUrl.Read(configuration);
    }

    /// <summary>The note's public link, if it has one. Owner-only.</summary>
    /// <param name="noteId">The note.</param>
    /// <returns>200 with the link, 204 when the note isn't published, 403 for a collaborator, 404 without access.</returns>
    [HttpGet]
    [ProducesResponseType<PublicLinkDto>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<ActionResult<PublicLinkDto>> Get(Guid noteId)
    {
        if (await OwnerCheckAsync(noteId) is { } refused) return refused;

        var link = await _db.NotePublicLinks.AsNoTracking().FirstOrDefaultAsync(l => l.NoteId == noteId);
        return link is null ? NoContent() : Ok(ToDto(link));
    }

    /// <summary>
    /// Publishes the note, or changes how it is published. The first call makes the link; later
    /// ones change its settings, and with <see cref="UpsertPublicLinkDto.Regenerate"/> replace its
    /// token. Every change ends the views already open (they open the link again). Owner-only.
    /// </summary>
    /// <param name="noteId">The note.</param>
    /// <param name="dto">The settings.</param>
    /// <returns>200 with the link, 400 for an expiry in the past, 403 for a collaborator, 404 without access.</returns>
    [HttpPut]
    public async Task<ActionResult<PublicLinkDto>> Upsert(Guid noteId, UpsertPublicLinkDto dto)
    {
        var callerId = User.GetUserId();
        if (await OwnerCheckAsync(noteId) is { } refused) return refused;

        var now = DateTime.UtcNow;
        var expires = dto.ExpiresAtUtc is { } at ? DateTime.SpecifyKind(at.ToUniversalTime(), DateTimeKind.Utc) : (DateTime?)null;
        if (expires is { } e && e <= now)
        {
            ModelState.AddModelError(nameof(UpsertPublicLinkDto.ExpiresAtUtc), "The expiry date must be in the future.");
            return ValidationProblem(ModelState);
        }

        var link = await _db.NotePublicLinks.FirstOrDefaultAsync(l => l.NoteId == noteId);
        if (link is null)
        {
            link = new NotePublicLink
            {
                Id = Guid.NewGuid(),
                NoteId = noteId,
                CreatedByUserId = callerId!.Value,
                CreatedAtUtc = now,
            };
            _links.SetToken(link);
            _db.NotePublicLinks.Add(link);
        }
        else
        {
            if (dto.Regenerate) _links.SetToken(link);
            link.Revision++;
        }

        if (dto.Password is not null) _links.SetPassword(link, dto.Password);
        else if (dto.ClearPassword) _links.SetPassword(link, null);
        link.ExpiresAtUtc = expires;
        link.AllowEdit = dto.AllowEdit;

        try
        {
            await _db.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            // Two first publishes of the same note at once: the unique index let one through. That
            // one is the link; answer with it rather than failing.
            _db.ChangeTracker.Clear();
            var existing = await _db.NotePublicLinks.AsNoTracking().FirstOrDefaultAsync(l => l.NoteId == noteId);
            if (existing is null) throw;
            return Ok(ToDto(existing));
        }

        // Only the owner sees that a note is published (NoteDto.IsPublic).
        await _notifier.NotifyAsync(callerId!.Value, RealtimeResources.Notes);
        return Ok(ToDto(link));
    }

    /// <summary>Takes the note off the internet: the link, and every view open on it, stop working. Owner-only.</summary>
    /// <param name="noteId">The note.</param>
    /// <returns>204, also when it wasn't published; 403 for a collaborator; 404 without access.</returns>
    [HttpDelete]
    public async Task<IActionResult> Delete(Guid noteId)
    {
        var callerId = User.GetUserId();
        if (await OwnerCheckAsync(noteId) is { } refused) return refused;

        var link = await _db.NotePublicLinks.FirstOrDefaultAsync(l => l.NoteId == noteId);
        if (link is not null)
        {
            _db.NotePublicLinks.Remove(link);
            await _db.SaveChangesAsync();
            await _notifier.NotifyAsync(callerId!.Value, RealtimeResources.Notes);
        }
        return NoContent();
    }

    /// <summary>Null when the caller owns the note; otherwise the response that refuses them.</summary>
    private async Task<ActionResult?> OwnerCheckAsync(Guid noteId)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.IsOwner) return Forbid();
        return null;
    }

    /// <summary>The owner's view of a link. The link is built only on <c>App:PublicBaseUrl</c>, never on the request.</summary>
    private PublicLinkDto ToDto(NotePublicLink link)
    {
        var token = _links.TokenOf(link) ?? "";
        return new PublicLinkDto
        {
            Token = token,
            Url = _publicBaseUrl is null || token.Length == 0 ? null : $"{_publicBaseUrl}/p#{token}",
            HasPassword = link.PasswordHash is not null,
            AllowEdit = link.AllowEdit,
            ExpiresAtUtc = link.ExpiresAtUtc,
            IsExpired = link.IsExpired(DateTime.UtcNow),
            CreatedAtUtc = link.CreatedAtUtc,
        };
    }
}
