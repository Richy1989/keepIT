using keepITCore.Data;
using keepITCore.Service;

namespace keepITCore.Notes;

/// <summary>
/// Opens the rendition of a note's attachment that a request asks for: the thumbnail, the
/// card-sized preview (made on first request and kept) or the original. Shared by
/// <see cref="NoteMediaController"/> (signed-in callers) and <see cref="PublicNotesController"/>
/// (visitors of a public link), so both serve exactly the same bytes. It decides nothing about
/// access: the caller has already done that.
/// </summary>
public sealed class NoteMediaReader(IMediaStorage storage, NoteMediaProcessor processor)
{
    /// <summary>
    /// Opens one rendition of <paramref name="media"/>.
    /// </summary>
    /// <param name="ownerId">The note's owner, whose folder holds the files.</param>
    /// <param name="media">The attachment.</param>
    /// <param name="size">"thumb" for small tiles, "preview" for note cards (at most 1280 px); anything else serves the original.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The stream and the file name it was read from (which decides the content type), or null when the file is missing.</returns>
    public async Task<(Stream Stream, string FileName)?> OpenAsync(
        Guid ownerId, NoteMedia media, string? size, CancellationToken ct)
    {
        var noteId = media.NoteId;
        var wantThumb = string.Equals(size, "thumb", StringComparison.OrdinalIgnoreCase);
        var wantPreview = string.Equals(size, "preview", StringComparison.OrdinalIgnoreCase);

        string fileName;
        Stream? stream;
        if (wantPreview && HasOwnPreview(media))
        {
            fileName = PreviewFileName(media);
            stream = storage.OpenRead(ownerId, noteId, fileName)
                ?? await CreatePreviewAsync(ownerId, noteId, media, fileName, ct);
        }
        else
        {
            // A preview request for an image that needs none falls through to the original, and so
            // does every request for audio: a voice note has exactly one rendition, and its
            // ThumbFileName is empty rather than naming a file that was never written.
            fileName = wantThumb && media.Kind == NoteMediaKind.Image ? media.ThumbFileName : media.FileName;
            stream = storage.OpenRead(ownerId, noteId, fileName);
        }

        return stream is null ? null : (stream, fileName);
    }

    /// <summary>
    /// The preview's file name. Derived from the media id instead of stored on the row, so every
    /// image ever uploaded has one waiting to be generated — no migration, no backfill.
    /// </summary>
    public static string PreviewFileName(NoteMedia media) => $"{media.Id:N}_preview.jpg";

    /// <summary>
    /// Whether the image gets a preview of its own. An original already within the preview size is
    /// its own preview, and a GIF is served as stored so an animation keeps its frames.
    /// </summary>
    private static bool HasOwnPreview(NoteMedia media) =>
        media.Kind == NoteMediaKind.Image
        && !media.FileName.EndsWith(".gif", StringComparison.OrdinalIgnoreCase)
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
        await using var original = storage.OpenRead(ownerId, noteId, media.FileName);
        if (original is null) return null;

        var preview = await processor.CreatePreviewAsync(original, ct);
        try
        {
            await storage.SaveAsync(ownerId, noteId, previewName, preview, ct);
        }
        catch (IOException)
        {
            // A concurrent request for the same preview got there first (Windows won't replace a
            // file another reader holds open). Its bytes are identical, and these are still good.
        }

        preview.Position = 0;
        return preview;
    }
}
