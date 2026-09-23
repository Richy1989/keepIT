using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Serialization;
using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Portability.Dtos;
using keepITCore.Infrastructure;
using keepITCore.Infrastructure.Security;
using keepITCore.Lists.Dtos;
using keepITCore.Notes;
using keepITCore.Service;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Portability;

/// <summary>
/// Hands a user their own data back as a zip: <c>keepit-export.json</c> (see
/// <see cref="NoteArchiveDto"/>) plus the original bytes of every attached image.
/// <para>
/// <b>Owner-scoped, not "own OR shared".</b> This is the one read in the app that deliberately
/// does <em>not</em> follow the usual access rule. A note shared with the caller is another user's
/// data sitting in their grid; writing it into a file they keep would outlive the owner revoking
/// the share, so the archive stops at what the caller owns.
/// </para>
/// <para>
/// The response is streamed: the manifest is built in memory (the API already returns a user's
/// whole grid in one response, so that is a size the app is designed for), but image bytes are
/// copied one file at a time straight into the zip, so a large account never lands in memory.
/// </para>
/// </summary>
[ApiController]
[Authorize]
[Route("api/export")]
[EnableRateLimiting(RateLimitPolicies.Export)]
public class ExportController : ControllerBase
{
    /// <summary>
    /// Matches what MVC puts on the wire — camelCase, and enums as their string names — so the
    /// archive's JSON is the same shape a client already knows how to read. Fixed here rather than
    /// taken from MVC's options so the archive format cannot drift with a host setting.
    /// </summary>
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
        WriteIndented = true,
    };

    private readonly AppDbContext _db;
    private readonly IMediaStorage _media;

    /// <summary>Injects the database context and the media storage port.</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="media">Storage port, used to read attached images back out.</param>
    public ExportController(AppDbContext db, IMediaStorage media)
    {
        _db = db;
        _media = media;
    }

    /// <summary>Streams the caller's notes, lists and images as a zip archive.</summary>
    /// <param name="ct">Cancellation token; a client that abandons the download stops the copy.</param>
    /// <returns>200 with <c>application/zip</c>, as a file attachment.</returns>
    [HttpGet]
    [Produces("application/zip")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> Get(CancellationToken ct)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();

        // Everything that can fail with a status code happens before a single byte is written:
        // once the zip starts streaming the response headers are gone and a 500 is no longer
        // expressible, so the caller would get a truncated file instead of an error.
        var archive = await BuildManifestAsync(ownerId.Value, ct);
        var files = await MediaFilesAsync(ownerId.Value, ct);

        var fileName = $"keepit-export-{DateTime.UtcNow:yyyy-MM-dd}.zip";
        Response.ContentType = "application/zip";
        Response.Headers.ContentDisposition = $"attachment; filename=\"{fileName}\"";

        // ZipArchive has no async write path — it writes to the stream it is given synchronously,
        // which Kestrel refuses by default (a slow client would pin a thread pool thread for the
        // whole download). The alternative is to spool the archive to a temp file and send that
        // asynchronously, which costs disk equal to the archive and delays the first byte. This
        // endpoint keeps the stream and lifts the restriction for this response only: memory stays
        // flat regardless of account size, and the number of threads that can be tied up is capped
        // by the tight rate limit above rather than by the thread pool.
        HttpContext.Features.Get<IHttpBodyControlFeature>()!.AllowSynchronousIO = true;

        using (var zip = new ZipArchive(Response.Body, ZipArchiveMode.Create, leaveOpen: true))
        {
            var manifest = zip.CreateEntry("keepit-export.json", CompressionLevel.Optimal);
            await using (var manifestStream = manifest.Open())
                await JsonSerializer.SerializeAsync(manifestStream, archive, JsonOptions, ct);

            foreach (var (noteId, storedName) in files)
            {
                // Missing is not an error: the orphan sweep or a concurrent delete can take a file
                // between the query and the copy. The manifest still lists it; import skips it.
                using var source = _media.OpenRead(ownerId.Value, noteId, storedName);
                if (source is null) continue;

                // Images are already compressed — deflating them again costs CPU and saves nothing.
                var entry = zip.CreateEntry($"media/{noteId}/{storedName}", CompressionLevel.NoCompression);
                await using var target = entry.Open();
                await source.CopyToAsync(target, ct);
            }
        }

        // The zip wrote the response itself; nothing further for MVC to serialize.
        return new EmptyResult();
    }

    /// <summary>Builds the manifest: the caller's lists, and the notes they own, projected as the API serves them.</summary>
    /// <param name="ownerId">The exporting user.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The populated archive manifest.</returns>
    private async Task<NoteArchiveDto> BuildManifestAsync(Guid ownerId, CancellationToken ct)
    {
        var lists = await _db.Lists.AsNoTracking()
            .Where(l => l.OwnerId == ownerId)
            .OrderBy(l => l.Name)
            .Select(l => new ListDto
            {
                Id = l.Id,
                Name = l.Name,
                Color = l.Color,
                CreatedAtUtc = l.CreatedAtUtc,
                // A snapshot of the sidebar count at export time, on the same "active view" basis
                // as ListsController. Informational only — import recomputes it.
                NoteCount = l.NoteLists.Count(nl =>
                    nl.UserId == ownerId
                    && !nl.Note.UserStates.Any(us => us.UserId == ownerId && (us.IsTrashed || us.IsArchived))),
            })
            .ToListAsync(ct);

        var notes = await OwnedNotesQuery(ownerId)
            .Include(n => n.ChecklistItems)
            .Include(n => n.NoteLists.Where(nl => nl.UserId == ownerId))
            .Include(n => n.NoteShares)
            .Include(n => n.Reminders.Where(r => r.UserId == ownerId))
            .Include(n => n.Media)
            .OrderBy(n => n.CreatedAtUtc)
            .ToListAsync(ct);

        var states = await _db.NoteUserStates.AsNoTracking()
            .Where(us => us.UserId == ownerId)
            .ToDictionaryAsync(us => us.NoteId, us => us, ct);

        return new NoteArchiveDto
        {
            ExportedAtUtc = DateTime.UtcNow,
            AppVersion = AppVersion.Current,
            Lists = lists,
            Notes = notes
                .Select(n => NoteProjection.ToDto(n, states.GetValueOrDefault(n.Id), ownerId))
                .ToList(),
        };
    }

    /// <summary>
    /// The stored file of every image on the caller's own notes, in note-then-position order.
    /// Originals only: a thumbnail is derived, and import regenerates it, so shipping both would
    /// double the archive for nothing.
    /// </summary>
    /// <param name="ownerId">The exporting user.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>Pairs of note id and stored file name.</returns>
    private async Task<List<(Guid NoteId, string FileName)>> MediaFilesAsync(Guid ownerId, CancellationToken ct)
    {
        var rows = await OwnedNotesQuery(ownerId)
            .SelectMany(n => n.Media)
            .OrderBy(m => m.NoteId).ThenBy(m => m.Order)
            .Select(m => new { m.NoteId, m.FileName })
            .ToListAsync(ct);

        return rows.Select(r => (r.NoteId, r.FileName)).ToList();
    }

    /// <summary>The caller's own notes — the single place the export's owner-only scope is expressed.</summary>
    /// <param name="ownerId">The exporting user.</param>
    /// <returns>An untracked query over the notes that user owns.</returns>
    private IQueryable<Note> OwnedNotesQuery(Guid ownerId) =>
        _db.Notes.AsNoTracking().Where(n => n.OwnerId == ownerId);
}
