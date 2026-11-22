using keepITCore.Data;
using keepITCore.Infrastructure.Security;
using keepITCore.Notes.Dtos;
using keepITCore.Service;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// What a visitor of a public link reaches, without an account (ARCHITECTURE.md "Public links").
/// <para>The token travels only in a request body (<see cref="Open"/>), never in a URL: the page
/// reads it from the link's fragment, which the browser never sends, so no log of ours ever holds
/// it. Opening returns a short-lived view ticket, which reading attachments and saving edits carry
/// in the <c>X-View-Ticket</c> header.</para>
/// <para>A link that doesn't exist, has expired, was revoked, or whose note is in its owner's trash
/// is the same 404 throughout.</para>
/// </summary>
[ApiController]
[AllowAnonymous]
[Route("api/public")]
public class PublicNotesController : ControllerBase
{
    /// <summary>The header a visitor's view ticket travels in.</summary>
    public const string TicketHeader = "X-View-Ticket";

    /// <summary>The note fields an editable link lets a visitor change. Never colour, background, type or attachments.</summary>
    private static readonly HashSet<NoteField> EditableFields = [NoteField.Title, NoteField.Body, NoteField.ChecklistItems];

    private readonly AppDbContext _db;
    private readonly PublicLinkService _links;
    private readonly NoteContentWriter _writer;
    private readonly NoteMediaReader _reader;
    private readonly NoteAccessService _access;
    private readonly IRealtimeNotifier _notifier;

    /// <summary>Injects the context, link secrets, the shared write and read paths, and the notifier.</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="links">Tokens, passwords and tickets.</param>
    /// <param name="writer">The note content write path, the same one signed-in editors use.</param>
    /// <param name="reader">Opens an attachment's rendition, the same way for everyone.</param>
    /// <param name="access">Gives the realtime recipients of an edited note.</param>
    /// <param name="notifier">Tells the owner's and collaborators' devices a visitor changed the note.</param>
    public PublicNotesController(
        AppDbContext db,
        PublicLinkService links,
        NoteContentWriter writer,
        NoteMediaReader reader,
        NoteAccessService access,
        IRealtimeNotifier notifier)
    {
        _db = db;
        _links = links;
        _writer = writer;
        _reader = reader;
        _access = access;
        _notifier = notifier;
    }

    /// <summary>
    /// Opens a public link: the note's content and a view ticket for reading its attachments (and
    /// saving edits, when the link allows them).
    /// </summary>
    /// <param name="dto">The token from the link, and the password if it has one.</param>
    /// <returns>200 with the note; 401 when a password is needed or was wrong; 404 when the link opens nothing.</returns>
    [HttpPost("open")]
    [EnableRateLimiting(RateLimitPolicies.PublicLink)]
    [ProducesResponseType<PublicNoteDto>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<PublicNoteDto>> Open(OpenPublicNoteDto dto)
    {
        MarkPrivate();
        var (result, link) = await _links.OpenAsync(dto.Token, dto.Password);
        return result switch
        {
            PublicLinkOpenResult.Opened => await LoadAsync(link!) is { } note ? Ok(note) : NotFound(),
            PublicLinkOpenResult.PasswordRequired => Unauthorized(),
            _ => NotFound(),
        };
    }

    /// <summary>Streams one attachment of the note the view ticket was issued for.</summary>
    /// <param name="mediaId">The attachment.</param>
    /// <param name="size">"thumb", "preview" or anything else for the original, as for a signed-in reader.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>200 with the bytes, or 404 for a bad ticket or an attachment of another note.</returns>
    [HttpGet("media/{mediaId:guid}")]
    public async Task<IActionResult> Media(Guid mediaId, [FromQuery] string? size, CancellationToken ct)
    {
        MarkPrivate();
        var link = await _links.FromTicketAsync(Request.Headers[TicketHeader]);
        if (link is null) return NotFound();

        var media = await _db.NoteMedia.AsNoTracking()
            .FirstOrDefaultAsync(m => m.Id == mediaId && m.NoteId == link.NoteId, ct);
        if (media is null) return NotFound();

        var ownerId = await _db.Notes.Where(n => n.Id == link.NoteId).Select(n => n.OwnerId).FirstAsync(ct);
        if (await _reader.OpenAsync(ownerId, media, size, ct) is not { } opened) return NotFound();
        var (stream, fileName) = opened;

        return File(stream, MediaContentTypes.For(fileName), enableRangeProcessing: true);
    }

    /// <summary>
    /// Saves a visitor's edit through an editable link: title, text and checklist only, named in
    /// <see cref="UpdateNoteDto.Fields"/>. Written through the same path as a signed-in editor's
    /// save (limits, checklist merge, retry), and pushed to the owner's and collaborators' devices.
    /// </summary>
    /// <param name="dto">The edit. <see cref="UpdateNoteDto.Fields"/> is required.</param>
    /// <returns>200 with the note as it now is; 400 for a field a visitor may not change; 403 for a read-only link; 404 for a bad ticket.</returns>
    [HttpPut("note")]
    [EnableRateLimiting(RateLimitPolicies.PublicLinkEdit)]
    [RequestSizeLimit(2 * 1024 * 1024)]
    [ProducesResponseType<PublicNoteDto>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<PublicNoteDto>> Update(UpdateNoteDto dto)
    {
        MarkPrivate();
        var link = await _links.FromTicketAsync(Request.Headers[TicketHeader]);
        if (link is null) return NotFound();
        // Forbid() would challenge a scheme; a visitor has none, so the status is set directly.
        if (!link.AllowEdit) return StatusCode(StatusCodes.Status403Forbidden);

        if (dto.Fields is null || dto.Fields.Any(f => !EditableFields.Contains(f)))
        {
            ModelState.AddModelError(nameof(UpdateNoteDto.Fields),
                "A public link may change only the title, the text and the checklist, and must name which.");
            return ValidationProblem(ModelState);
        }

        switch (await _writer.UpdateAsync(link.NoteId, dto))
        {
            case NoteContentWriteResult.NotFound:
                return NotFound();
            case NoteContentWriteResult.ChecklistTooLong:
                ModelState.AddModelError(nameof(UpdateNoteDto.ChecklistItems),
                    $"A checklist holds at most {NoteLimits.ChecklistItems} items.");
                return ValidationProblem(ModelState);
        }

        if (dto.Fields.Count > 0)
        {
            // Content is shared: the owner and every collaborator see the visitor's change live.
            var recipients = await _access.RecipientIdsAsync(link.NoteId);
            await Task.WhenAll(recipients.Select(uid => _notifier.NotifyAsync(uid, RealtimeResources.Notes)));
        }

        return await LoadAsync(link) is { } note ? Ok(note) : NotFound();
    }

    /// <summary>Keeps a visitor's responses out of shared caches and search engines.</summary>
    private void MarkPrivate()
    {
        Response.Headers.CacheControl = "private, no-store";
        Response.Headers["X-Robots-Tag"] = "noindex, nofollow";
    }

    /// <summary>The note behind <paramref name="link"/> as a visitor sees it, with a fresh ticket.</summary>
    private async Task<PublicNoteDto?> LoadAsync(NotePublicLink link)
    {
        var note = await _db.Notes.AsNoTracking()
            .Where(n => n.Id == link.NoteId)
            .Include(n => n.ChecklistItems)
            .Include(n => n.Media)
            .FirstOrDefaultAsync();
        if (note is null) return null;

        var ticket = _links.IssueTicket(link, out var ticketExpires);
        return new PublicNoteDto
        {
            Type = note.Type,
            Title = note.Title,
            Body = note.Body,
            Color = note.Color,
            Background = note.Background,
            UpdatedAtUtc = note.UpdatedAtUtc,
            ChecklistItems = note.ChecklistItems
                .OrderBy(c => c.Order)
                .Select(c => new ChecklistItemDto { Id = c.Id, Text = c.Text, Indent = c.Indent, IsChecked = c.IsChecked, Order = c.Order })
                .ToList(),
            Media = note.Media
                .OrderBy(m => m.Order)
                .Select(m => new NoteMediaDto
                {
                    Id = m.Id,
                    Kind = m.Kind,
                    DurationMs = m.DurationMs,
                    Width = m.Width,
                    Height = m.Height,
                    ByteSize = m.ByteSize,
                    Order = m.Order,
                    CreatedAtUtc = m.CreatedAtUtc,
                })
                .ToList(),
            CanEdit = link.AllowEdit,
            ViewTicket = ticket,
            TicketExpiresAtUtc = ticketExpires,
        };
    }
}
