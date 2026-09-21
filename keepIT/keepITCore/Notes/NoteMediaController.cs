using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Notes.Dtos;
using keepITCore.Service;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace keepITCore.Notes;

/// <summary>
/// Image attachments on a note. Media is a sub-resource of the note, so authorization is one
/// <see cref="NoteAccessService"/> call on the parent: reading bytes needs any access, attaching and
/// removing need Editor access. A collaborator reaches a shared note's images through the note, never
/// by owning the media.
/// </summary>
[ApiController]
[Authorize]
[Route("api/notes/{noteId:guid}/media")]
public class NoteMediaController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IMediaStorage _storage;
    private readonly NoteMediaProcessor _processor;
    private readonly NoteAccessService _access;
    private readonly IRealtimeNotifier _notifier;
    private readonly MediaOptions _options;

    /// <summary>Injects the context, storage, processor, access resolver, notifier and limits.</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="storage">Where the bytes live.</param>
    /// <param name="processor">Validation, EXIF stripping, resize and thumbnailing.</param>
    /// <param name="access">Resolves "own OR shared" access and the realtime recipient set.</param>
    /// <param name="notifier">Pushes change signals to affected users' devices.</param>
    /// <param name="options">Upload limits.</param>
    public NoteMediaController(
        AppDbContext db,
        IMediaStorage storage,
        NoteMediaProcessor processor,
        NoteAccessService access,
        IRealtimeNotifier notifier,
        IOptions<MediaOptions> options)
    {
        _db = db;
        _storage = storage;
        _processor = processor;
        _access = access;
        _notifier = notifier;
        _options = options.Value;
    }

    /// <summary>
    /// Attaches one image to a note. One file per request: a multi-select loops client-side so each
    /// image carries its own progress and its own failure.
    /// </summary>
    /// <param name="noteId">The note to attach to.</param>
    /// <param name="file">The image (jpg/png/webp/gif).</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>201 with the created media, 400/409/413 on a refused upload, 403 for a viewer, 404 without access.</returns>
    [HttpPost]
    // Answers over-sized uploads with 413 before model binding reads the body; the two framework
    // limits below stay as the backstop for a request with no Content-Length.
    [MaxMediaUploadSize]
    [RequestSizeLimit(12 * 1024 * 1024)] // 10 MB payload + multipart overhead headroom
    [RequestFormLimits(MultipartBodyLengthLimit = 12 * 1024 * 1024)]
    public async Task<ActionResult<NoteMediaDto>> Upload(Guid noteId, IFormFile file, CancellationToken ct)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.CanEdit) return Forbid();

        if (file is null || file.Length == 0) return BadRequest("No file provided.");
        if (file.Length > _options.MaxImageBytes)
            return StatusCode(StatusCodes.Status413PayloadTooLarge,
                $"Image too large (max {_options.MaxImageBytes / (1024 * 1024)} MB).");

        var existing = await _db.NoteMedia.CountAsync(m => m.NoteId == noteId, ct);
        if (existing >= _options.MaxImagesPerNote)
            return Conflict($"This note already has {_options.MaxImagesPerNote} images.");

        // The processor buffers the upload itself, once it's this upload's turn to decode.
        await using var upload = file.OpenReadStream();
        var result = await _processor.ProcessAsync(upload, ct);
        if (result.Reason == MediaRejection.TooManyPixels)
            return StatusCode(StatusCodes.Status413PayloadTooLarge,
                $"Image too large (max {_options.MaxImagePixels / 1_000_000} megapixels).");
        if (result.Image is null)
            return BadRequest(result.Reason switch
            {
                MediaRejection.Heic =>
                    "HEIC images aren't supported yet. On iPhone, set Camera → Formats to "
                    + "\"Most Compatible\", or share the photo as JPEG.",
                MediaRejection.Corrupt => "That image couldn't be read.",
                _ => "The file is not a valid image.",
            });

        var image = result.Image;
        var ownerId = await _db.Notes.Where(n => n.Id == noteId).Select(n => n.OwnerId).FirstAsync(ct);

        var mediaId = Guid.NewGuid();
        var fileName = $"{mediaId:N}{image.Extension}";
        var thumbName = $"{mediaId:N}_thumb{image.ThumbnailExtension}";

        // Bytes first, row second: a row pointing at missing bytes breaks rendering on every client,
        // whereas a file with no row is invisible and the orphan sweep collects it.
        var byteSize = await _storage.SaveAsync(ownerId, noteId, fileName, image.Original, ct);
        await _storage.SaveAsync(ownerId, noteId, thumbName, image.Thumbnail, ct);

        var maxOrder = await _db.NoteMedia.Where(m => m.NoteId == noteId)
            .Select(m => (int?)m.Order).MaxAsync(ct);

        var media = new NoteMedia
        {
            Id = mediaId,
            NoteId = noteId,
            FileName = fileName,
            ThumbFileName = thumbName,
            Width = image.Width,
            Height = image.Height,
            ByteSize = byteSize,
            Order = (maxOrder ?? -1) + 1,
            CreatedAtUtc = DateTime.UtcNow,
        };

        _db.NoteMedia.Add(media);

        try
        {
            await _db.SaveChangesAsync(ct);
        }
        catch
        {
            // Compensate so we never leave bytes the database doesn't know about.
            _storage.Delete(ownerId, noteId, fileName);
            _storage.Delete(ownerId, noteId, thumbName);
            throw;
        }

        await NotifyRecipientsAsync(noteId);

        return CreatedAtAction(nameof(Get), new { noteId, mediaId }, ToDto(media));
    }

    /// <summary>Streams one image. Any read access to the note is enough.</summary>
    /// <param name="noteId">The note.</param>
    /// <param name="mediaId">The media item.</param>
    /// <param name="size">"thumb" for small tiles, "preview" for note cards (at most 1280 px); anything else serves the original.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>200 with the bytes, or 404 when it is missing or the caller has no access.</returns>
    [HttpGet("{mediaId:guid}")]
    public async Task<IActionResult> Get(Guid noteId, Guid mediaId, [FromQuery] string? size, CancellationToken ct)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        // Same 404 for "no access" and "doesn't exist" — the non-enumeration stance.
        if (await _access.ResolveAsync(noteId, callerId.Value) is null) return NotFound();

        var media = await _db.NoteMedia.AsNoTracking()
            .FirstOrDefaultAsync(m => m.Id == mediaId && m.NoteId == noteId, ct);
        if (media is null) return NotFound();

        var ownerId = await _db.Notes.Where(n => n.Id == noteId).Select(n => n.OwnerId).FirstAsync(ct);
        var wantThumb = string.Equals(size, "thumb", StringComparison.OrdinalIgnoreCase);
        var wantPreview = string.Equals(size, "preview", StringComparison.OrdinalIgnoreCase);

        string fileName;
        Stream? stream;
        if (wantPreview && HasOwnPreview(media))
        {
            fileName = PreviewFileName(media);
            stream = _storage.OpenRead(ownerId, noteId, fileName)
                ?? await CreatePreviewAsync(ownerId, noteId, media, fileName, ct);
        }
        else
        {
            // A preview request for an image that needs none falls through to the original.
            fileName = wantThumb ? media.ThumbFileName : media.FileName;
            stream = _storage.OpenRead(ownerId, noteId, fileName);
        }

        if (stream is null) return NotFound();

        // A media id's bytes never change, so this cache directive is honest.
        Response.Headers.CacheControl = "private, max-age=31536000, immutable";
        return File(stream, MediaContentTypes.For(fileName), enableRangeProcessing: true);
    }

    /// <summary>Removes one image from a note. Editor access required.</summary>
    /// <param name="noteId">The note.</param>
    /// <param name="mediaId">The media item.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>204 on success, 403 for a viewer, 404 without access or when it doesn't exist.</returns>
    [HttpDelete("{mediaId:guid}")]
    public async Task<IActionResult> Delete(Guid noteId, Guid mediaId, CancellationToken ct)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.CanEdit) return Forbid();

        var media = await _db.NoteMedia.FirstOrDefaultAsync(m => m.Id == mediaId && m.NoteId == noteId, ct);
        if (media is null) return NotFound();

        var ownerId = await _db.Notes.Where(n => n.Id == noteId).Select(n => n.OwnerId).FirstAsync(ct);

        // Capture the names before the row goes — after SaveChanges they're unrecoverable.
        var fileName = media.FileName;
        var thumbName = media.ThumbFileName;

        _db.NoteMedia.Remove(media);
        await _db.SaveChangesAsync(ct);

        _storage.Delete(ownerId, noteId, fileName);
        _storage.Delete(ownerId, noteId, thumbName);
        // Only exists once someone has viewed the image in a card; Delete tolerates a missing file.
        _storage.Delete(ownerId, noteId, PreviewFileName(media));

        await NotifyRecipientsAsync(noteId);
        return NoContent();
    }

    // ---- helpers ----

    /// <summary>
    /// The preview's file name. Derived from the media id instead of stored on the row, so every
    /// image ever uploaded has one waiting to be generated — no migration, no backfill.
    /// </summary>
    private static string PreviewFileName(NoteMedia media) => $"{media.Id:N}_preview.jpg";

    /// <summary>
    /// Whether the image gets a preview of its own. An original already within the preview size is
    /// its own preview, and a GIF is served as stored so an animation keeps its frames.
    /// </summary>
    private static bool HasOwnPreview(NoteMedia media) =>
        !media.FileName.EndsWith(".gif", StringComparison.OrdinalIgnoreCase)
        && Math.Max(media.Width, media.Height) > NoteMediaProcessor.PreviewEdge;

    /// <summary>
    /// Makes a preview from the stored original on first request and keeps it, so each image pays
    /// for it once. Generated on demand rather than at upload so images uploaded before previews
    /// existed get one too.
    /// </summary>
    /// <returns>The preview, or null when the original itself is missing.</returns>
    private async Task<Stream?> CreatePreviewAsync(
        Guid ownerId, Guid noteId, NoteMedia media, string previewName, CancellationToken ct)
    {
        await using var original = _storage.OpenRead(ownerId, noteId, media.FileName);
        if (original is null) return null;

        var preview = await _processor.CreatePreviewAsync(original, ct);
        try
        {
            await _storage.SaveAsync(ownerId, noteId, previewName, preview, ct);
        }
        catch (IOException)
        {
            // A concurrent request for the same preview got there first (Windows won't replace a
            // file another reader holds open). Its bytes are identical, and these are still good.
        }

        preview.Position = 0;
        return preview;
    }

    /// <summary>Tells the owner and every collaborator that this note's content changed.</summary>
    /// <param name="noteId">The note whose recipients to reach.</param>
    private async Task NotifyRecipientsAsync(Guid noteId)
    {
        var recipients = await _access.RecipientIdsAsync(noteId);
        await Task.WhenAll(recipients.Select(uid => _notifier.NotifyAsync(uid, RealtimeResources.Notes)));
    }

    /// <summary>Projects a stored row onto the wire shape.</summary>
    /// <param name="m">The stored row.</param>
    /// <returns>The DTO.</returns>
    private static NoteMediaDto ToDto(NoteMedia m) => new()
    {
        Id = m.Id,
        Width = m.Width,
        Height = m.Height,
        ByteSize = m.ByteSize,
        Order = m.Order,
        CreatedAtUtc = m.CreatedAtUtc,
    };
}
