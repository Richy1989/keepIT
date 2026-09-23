using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Serialization;
using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Infrastructure.Security;
using keepITCore.Notes.Dtos;
using keepITCore.Portability.Dtos;
using keepITCore.Service;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace keepITCore.Portability;

/// <summary>
/// Reads an archive written by <see cref="ExportController"/> back into the caller's account.
/// <para>
/// <b>Import only ever adds.</b> Every note in the archive arrives as a brand-new note with a new
/// id, and nothing already in the account is touched. Importing the same file twice therefore
/// produces duplicates — which is the deliberate trade: the operation that could destroy a user's
/// notes is the one operation that must not be able to. Matching by id ("restore over the top") is
/// a later mode, once the format has some mileage on it.
/// </para>
/// <para>
/// Everything the archive supplies is treated as hostile input. Ids in the file are never reused,
/// file names in the file never reach the disk (entries are matched by id and rewritten with
/// server-generated names, so there is no path to traverse), and images are re-decoded through
/// <see cref="NoteMediaProcessor"/> — the same validation, pixel bound, metadata stripping and
/// thumbnailing an upload gets. An archive is a file from the internet like any other.
/// </para>
/// </summary>
[ApiController]
[Authorize]
[Route("api/import")]
[EnableRateLimiting(RateLimitPolicies.Import)]
public class ImportController : ControllerBase
{
    /// <summary>The manifest's name at the root of the archive.</summary>
    private const string ManifestName = "keepit-export.json";

    /// <summary>
    /// Ceiling on the whole upload. Generous enough for a real account's images, low enough that a
    /// single request cannot fill an instance's disk.
    /// </summary>
    private const long MaxArchiveBytes = 256L * 1024 * 1024;

    /// <summary>
    /// Ceiling on the manifest's <em>uncompressed</em> size, checked before it is read. A few
    /// hundred notes of text is a couple of megabytes; this is the guard against an archive whose
    /// tiny JSON entry expands into gigabytes.
    /// </summary>
    private const long MaxManifestBytes = 64L * 1024 * 1024;

    /// <summary>Matches the writer's options, so the archive round-trips exactly.</summary>
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
    };

    private readonly AppDbContext _db;
    private readonly IMediaStorage _storage;
    private readonly NoteMediaProcessor _processor;
    private readonly IRealtimeNotifier _notifier;
    private readonly MediaOptions _options;

    /// <summary>Injects the context, storage, image processor, notifier and media limits.</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="storage">Where imported image bytes are written.</param>
    /// <param name="processor">Re-validates and re-encodes every image in the archive.</param>
    /// <param name="notifier">Pushes change signals to the caller's devices.</param>
    /// <param name="options">Media limits, applied to imported images exactly as to uploads.</param>
    public ImportController(
        AppDbContext db,
        IMediaStorage storage,
        NoteMediaProcessor processor,
        IRealtimeNotifier notifier,
        IOptions<MediaOptions> options)
    {
        _db = db;
        _storage = storage;
        _processor = processor;
        _notifier = notifier;
        _options = options.Value;
    }

    /// <summary>Imports a keepIT export archive into the caller's account, adding to what is there.</summary>
    /// <param name="file">The <c>.zip</c> written by the export endpoint.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>200 with what was added, 400 for an archive that cannot be read, 413 when it is too big.</returns>
    [HttpPost]
    // Declared so the generated clients know a refusal carries a plain-text explanation: it names
    // what was wrong with the archive, which is the only useful thing to show the user.
    [ProducesResponseType<ImportResultDto>(StatusCodes.Status200OK)]
    [ProducesResponseType<string>(StatusCodes.Status400BadRequest, "text/plain")]
    [RequestSizeLimit(MaxArchiveBytes)]
    [RequestFormLimits(MultipartBodyLengthLimit = MaxArchiveBytes)]
    public async Task<ActionResult<ImportResultDto>> Post(IFormFile file, CancellationToken ct)
    {
        var ownerId = User.GetUserId();
        if (ownerId is null) return Unauthorized();
        if (file is null || file.Length == 0) return BadRequest("No file provided.");

        // Spooled to a real file first: ZipArchive reads the central directory at the end and so
        // needs to seek, which a form file's stream does not reliably support.
        var spoolPath = Path.Combine(TempFolder(), $"import-{Guid.NewGuid():N}.zip");
        try
        {
            await using (var spool = System.IO.File.Create(spoolPath))
                await file.CopyToAsync(spool, ct);

            return await ImportAsync(spoolPath, ownerId.Value, ct);
        }
        catch (InvalidDataException)
        {
            return BadRequest("That file isn't a readable zip archive.");
        }
        finally
        {
            TryDelete(spoolPath);
        }
    }

    /// <summary>Reads the spooled archive and applies it.</summary>
    /// <param name="spoolPath">The archive on disk.</param>
    /// <param name="ownerId">The importing user, who owns everything created.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The action result to return to the caller.</returns>
    private async Task<ActionResult<ImportResultDto>> ImportAsync(
        string spoolPath, Guid ownerId, CancellationToken ct)
    {
        using var zip = ZipFile.OpenRead(spoolPath);

        // Lenient about a wrapping folder: people re-zip a folder they unpacked, and the manifest
        // ends up one level down. The shallowest match wins.
        var manifestEntry = zip.Entries
            .Where(e => e.Name.Equals(ManifestName, StringComparison.OrdinalIgnoreCase))
            .OrderBy(e => e.FullName.Count(c => c == '/'))
            .FirstOrDefault();

        if (manifestEntry is null)
            return BadRequest(
                $"This doesn't look like a keepIT export: no {ManifestName} inside. "
                + "Exports from other apps aren't supported yet.");

        if (manifestEntry.Length > MaxManifestBytes)
            return BadRequest("The archive's manifest is implausibly large.");

        NoteArchiveDto? archive;
        try
        {
            await using var manifestStream = manifestEntry.Open();
            archive = await JsonSerializer.DeserializeAsync<NoteArchiveDto>(manifestStream, JsonOptions, ct);
        }
        catch (JsonException)
        {
            return BadRequest($"The archive's {ManifestName} could not be read.");
        }

        if (archive is null) return BadRequest($"The archive's {ManifestName} was empty.");

        // Forward compatibility runs one way: an older server cannot know what a newer archive
        // means, and guessing would silently drop whatever was added.
        if (archive.SchemaVersion > NoteArchiveDto.CurrentSchemaVersion)
            return BadRequest(
                $"This archive was written by a newer version of keepIT "
                + $"(format {archive.SchemaVersion}; this server reads {NoteArchiveDto.CurrentSchemaVersion}). "
                + "Update the server and try again.");

        var result = new ImportResultDto();
        var listIdMap = await ImportListsAsync(archive, ownerId, result, ct);
        var mediaIndex = IndexMedia(zip);

        // Note folders whose bytes are already on disk. If the save fails we take them with us, so
        // an import can never leave files the database knows nothing about.
        var writtenFolders = new List<Guid>();

        try
        {
            foreach (var source in archive.Notes)
            {
                var note = NewNote(source, ownerId, listIdMap);
                _db.Notes.Add(note);
                result.NotesImported++;

                if (source.Media.Count > 0)
                {
                    writtenFolders.Add(note.Id);
                    await ImportMediaAsync(source, note, ownerId, mediaIndex, result, ct);
                }
            }

            await _db.SaveChangesAsync(ct);
        }
        catch
        {
            foreach (var noteId in writtenFolders) _storage.DeleteNote(ownerId, noteId);
            throw;
        }

        // Everything the caller sees changed at once; their other devices have to refetch both.
        await _notifier.NotifyAsync(ownerId, RealtimeResources.Notes, RealtimeResources.Lists);
        return Ok(result);
    }

    /// <summary>
    /// Resolves the archive's lists to the caller's. A list whose name the caller already uses is
    /// reused rather than duplicated — filing a note into an existing list destroys nothing, while
    /// a second "Groceries" on every import would make the sidebar useless within a few restores.
    /// </summary>
    /// <param name="archive">The manifest being imported.</param>
    /// <param name="ownerId">The importing user.</param>
    /// <param name="result">Counters to update.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>A map from the archive's list ids to the caller's.</returns>
    private async Task<Dictionary<Guid, Guid>> ImportListsAsync(
        NoteArchiveDto archive, Guid ownerId, ImportResultDto result, CancellationToken ct)
    {
        var existing = await _db.Lists
            .Where(l => l.OwnerId == ownerId)
            .ToDictionaryAsync(l => l.Name.ToLower(), l => l.Id, ct);

        var map = new Dictionary<Guid, Guid>();

        foreach (var source in archive.Lists)
        {
            var name = (source.Name ?? "").Trim();
            if (name.Length == 0) continue;

            if (existing.TryGetValue(name.ToLowerInvariant(), out var existingId))
            {
                map[source.Id] = existingId;
                result.ListsReused++;
                continue;
            }

            var list = new KeepList
            {
                Id = Guid.NewGuid(),
                OwnerId = ownerId,
                Name = name,
                Color = source.Color,
                CreatedAtUtc = source.CreatedAtUtc == default ? DateTime.UtcNow : source.CreatedAtUtc,
            };
            _db.Lists.Add(list);

            existing[name.ToLowerInvariant()] = list.Id;
            map[source.Id] = list.Id;
            result.ListsCreated++;
        }

        return map;
    }

    /// <summary>
    /// Builds one note from its archived form: new ids throughout, original timestamps kept, and the
    /// caller's private view (pin/archive/trash, reminder, list membership) restored as their own.
    /// </summary>
    /// <param name="source">The archived note.</param>
    /// <param name="ownerId">The importing user, who owns the result.</param>
    /// <param name="listIdMap">Archive list id to the caller's list id.</param>
    /// <returns>The new note, not yet added to the context.</returns>
    private static Note NewNote(NoteDto source, Guid ownerId, Dictionary<Guid, Guid> listIdMap)
    {
        var now = DateTime.UtcNow;
        var note = new Note
        {
            Id = Guid.NewGuid(),
            OwnerId = ownerId,
            Type = source.Type,
            Title = source.Title,
            Body = source.Body,
            Color = source.Color,
            // Kept, not reset: a restored backup that claimed every note was written today would
            // sort the grid into nonsense.
            CreatedAtUtc = source.CreatedAtUtc == default ? now : source.CreatedAtUtc,
            UpdatedAtUtc = source.UpdatedAtUtc == default ? now : source.UpdatedAtUtc,
        };

        var order = 0;
        foreach (var item in source.ChecklistItems.OrderBy(c => c.Order))
        {
            note.ChecklistItems.Add(new ChecklistItem
            {
                Id = Guid.NewGuid(),
                NoteId = note.Id,
                Text = item.Text,
                IsChecked = item.IsChecked,
                Order = order++,
            });
        }

        note.UserStates.Add(new NoteUserState
        {
            NoteId = note.Id,
            UserId = ownerId,
            IsPinned = source.IsPinned,
            IsArchived = source.IsArchived,
            IsTrashed = source.IsTrashed,
        });

        if (source.RemindAtUtc is { } remindAt)
        {
            var recurrence = source.ReminderRecurrence ?? ReminderRecurrence.None;

            // A one-time reminder whose moment has passed is imported already fired. Otherwise
            // restoring a year-old backup would hand the dispatcher a pile of overdue reminders and
            // the user would get a notification storm for things they dealt with long ago.
            // Recurring ones need no help: the dispatcher advances them to the next occurrence.
            var fired = source.ReminderFired || (recurrence == ReminderRecurrence.None && remindAt <= DateTime.UtcNow);

            note.Reminders.Add(new NoteReminder
            {
                NoteId = note.Id,
                UserId = ownerId,
                RemindAtUtc = remindAt,
                Recurrence = recurrence,
                FiredAtUtc = fired ? DateTime.UtcNow : null,
            });
        }

        foreach (var archivedListId in source.ListIds)
        {
            if (!listIdMap.TryGetValue(archivedListId, out var listId)) continue;
            note.NoteLists.Add(new NoteList { NoteId = note.Id, ListId = listId, UserId = ownerId });
        }

        return note;
    }

    /// <summary>
    /// Re-attaches a note's images, each one re-decoded and re-encoded exactly as an upload would
    /// be. A skipped image is a warning, never a failed import.
    /// </summary>
    /// <param name="source">The archived note, naming the images it expects.</param>
    /// <param name="note">The new note the images attach to.</param>
    /// <param name="ownerId">The importing user.</param>
    /// <param name="mediaIndex">Archive entries by (archived note id, archived media id).</param>
    /// <param name="result">Counters and warnings to update.</param>
    /// <param name="ct">Cancellation token.</param>
    private async Task ImportMediaAsync(
        NoteDto source,
        Note note,
        Guid ownerId,
        Dictionary<(Guid NoteId, Guid MediaId), ZipArchiveEntry> mediaIndex,
        ImportResultDto result,
        CancellationToken ct)
    {
        var label = string.IsNullOrWhiteSpace(source.Title) ? "an untitled note" : $"\"{source.Title}\"";
        var order = 0;

        foreach (var archived in source.Media.OrderBy(m => m.Order))
        {
            if (order >= _options.MaxImagesPerNote)
            {
                result.ImagesSkipped++;
                result.Warnings.Add($"{label}: only the first {_options.MaxImagesPerNote} images were imported.");
                continue;
            }

            if (!mediaIndex.TryGetValue((source.Id, archived.Id), out var entry))
            {
                result.ImagesSkipped++;
                result.Warnings.Add($"{label}: an image listed in the archive was missing from it.");
                continue;
            }

            // The declared uncompressed size, checked before anything is decompressed.
            if (entry.Length > _options.MaxImageBytes)
            {
                result.ImagesSkipped++;
                result.Warnings.Add($"{label}: an image was over the {_options.MaxImageBytes / (1024 * 1024)} MB limit.");
                continue;
            }

            await using var bytes = entry.Open();
            var processed = await _processor.ProcessAsync(bytes, ct);
            if (processed.Image is null)
            {
                result.ImagesSkipped++;
                result.Warnings.Add($"{label}: an image could not be read ({Describe(processed.Reason)}).");
                continue;
            }

            var image = processed.Image;
            var mediaId = Guid.NewGuid();
            var fileName = $"{mediaId:N}{image.Extension}";
            var thumbName = $"{mediaId:N}_thumb{image.ThumbnailExtension}";

            var byteSize = await _storage.SaveAsync(ownerId, note.Id, fileName, image.Original, ct);
            await _storage.SaveAsync(ownerId, note.Id, thumbName, image.Thumbnail, ct);

            note.Media.Add(new NoteMedia
            {
                Id = mediaId,
                NoteId = note.Id,
                FileName = fileName,
                ThumbFileName = thumbName,
                Width = image.Width,
                Height = image.Height,
                ByteSize = byteSize,
                Order = order++,
                CreatedAtUtc = archived.CreatedAtUtc == default ? DateTime.UtcNow : archived.CreatedAtUtc,
            });

            result.ImagesImported++;
        }
    }

    /// <summary>Indexes the archive's image entries by the ids in their path.</summary>
    /// <param name="zip">The open archive.</param>
    /// <returns>Entries keyed by (archived note id, archived media id).</returns>
    private static Dictionary<(Guid NoteId, Guid MediaId), ZipArchiveEntry> IndexMedia(ZipArchive zip)
    {
        var index = new Dictionary<(Guid, Guid), ZipArchiveEntry>();

        foreach (var entry in zip.Entries)
        {
            // Directory entries have an empty Name. Nothing here is resolved as a path — the ids
            // are parsed out and the bytes are rewritten under a server-generated name — so a
            // hostile entry name has nowhere to escape to.
            if (entry.Name.Length == 0) continue;

            var parts = entry.FullName.Split('/');
            var at = Array.LastIndexOf(parts, "media");
            if (at < 0 || parts.Length - at != 3) continue;
            if (!Guid.TryParse(parts[at + 1], out var noteId)) continue;
            if (!Guid.TryParse(Path.GetFileNameWithoutExtension(parts[at + 2]), out var mediaId)) continue;

            index[(noteId, mediaId)] = entry;
        }

        return index;
    }

    /// <summary>Turns a rejection into something worth showing a user.</summary>
    /// <param name="reason">Why the processor refused the image.</param>
    /// <returns>A short explanation.</returns>
    private static string Describe(MediaRejection reason) => reason switch
    {
        MediaRejection.Heic => "HEIC images aren't supported",
        MediaRejection.Corrupt => "the file was damaged",
        MediaRejection.TooManyPixels => "it was too many megapixels",
        _ => "it wasn't a valid image",
    };

    /// <summary>The scratch folder for spooled uploads, under the data root so it shares its volume.</summary>
    /// <returns>The folder path, created if needed.</returns>
    private static string TempFolder()
    {
        var path = Path.Combine(FolderManagement.RootPath, "tmp");
        Directory.CreateDirectory(path);
        return path;
    }

    /// <summary>Best-effort cleanup of the spooled upload.</summary>
    /// <param name="path">The file to remove.</param>
    private static void TryDelete(string path)
    {
        try
        {
            if (System.IO.File.Exists(path)) System.IO.File.Delete(path);
        }
        catch (IOException)
        {
            // A leftover spool file is harmless; it is inside the data root and named uniquely.
        }
    }
}
