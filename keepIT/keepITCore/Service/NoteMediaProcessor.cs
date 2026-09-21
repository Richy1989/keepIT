using keepITCore.Infrastructure;
using Microsoft.Extensions.Options;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Formats;
using SixLabors.ImageSharp.Formats.Gif;
using SixLabors.ImageSharp.Formats.Jpeg;
using SixLabors.ImageSharp.Processing;

namespace keepITCore.Service;

/// <summary>Why an upload was refused; maps to the controller's status code and message.</summary>
public enum MediaRejection
{
    /// <summary>Accepted.</summary>
    None,

    /// <summary>Content carries no recognised image signature.</summary>
    NotAnImage,

    /// <summary>A real image, but HEIC — recognised purely so it can be refused by name.</summary>
    Heic,

    /// <summary>A recognised signature that the decoder could not actually read.</summary>
    Corrupt,

    /// <summary>
    /// Its header declares more pixels than <see cref="MediaOptions.MaxImagePixels"/> allows.
    /// Refused before decoding, which is where an oversized image would cost memory.
    /// </summary>
    TooManyPixels,
}

/// <summary>A processed upload, ready to be written to storage. Both streams are positioned at 0.</summary>
/// <param name="Original">The re-encoded original.</param>
/// <param name="Extension">Extension for the original, including the dot.</param>
/// <param name="Width">Pixel width of the original.</param>
/// <param name="Height">Pixel height of the original.</param>
/// <param name="Thumbnail">The grid thumbnail.</param>
/// <param name="ThumbnailExtension">Extension for the thumbnail, including the dot.</param>
public sealed record ProcessedImage(
    Stream Original, string Extension, int Width, int Height, Stream Thumbnail, string ThumbnailExtension);

/// <summary>The outcome of processing: an image, or the reason it was refused.</summary>
/// <param name="Image">The processed image when <paramref name="Reason"/> is <see cref="MediaRejection.None"/>.</param>
/// <param name="Reason">Why it was refused, if it was.</param>
public sealed record MediaProcessResult(ProcessedImage? Image, MediaRejection Reason);

/// <summary>
/// Turns an uploaded file into the two blobs we store. Originals are re-encoded rather than kept
/// verbatim, because that is the only way to strip metadata: phone photos carry GPS, and a shared
/// note would otherwise hand a collaborator the coordinates of the photographer's home. The
/// accepted trade-off is that pixel-exact originals are not preserved.
/// <para>
/// Decoding is the expensive step, and its cost follows the pixel count, not the upload's size, so
/// it is bounded three ways: the declared size is checked from the header first
/// (<see cref="MediaOptions.MaxImagePixels"/>), only the first frame of an animation is ever
/// decoded, and at most <see cref="MaxConcurrentDecodes"/> uploads are processed at once.
/// </para>
/// </summary>
public class NoteMediaProcessor
{
    private const int MaxOriginalEdge = 2560;
    private const int ThumbnailEdge = 400;
    private const int JpegQuality = 88;

    /// <summary>Uploads processed at the same time; the rest wait their turn.</summary>
    public const int MaxConcurrentDecodes = 2;

    /// <summary>
    /// Shared by every instance: the processor is scoped, but memory is a whole-process budget.
    /// </summary>
    private static readonly SemaphoreSlim DecodeSlots = new(MaxConcurrentDecodes, MaxConcurrentDecodes);

    /// <summary>
    /// First frame only. Every frame decodes to a full canvas, so a small GIF with thousands of
    /// frames would otherwise cost thousands of images' memory. It loses nothing: a GIF is stored
    /// as uploaded and thumbnailed from frame one, and other formats are re-encoded as a single
    /// JPEG frame anyway.
    /// </summary>
    private static readonly DecoderOptions Decoding = new() { MaxFrames = 1 };

    private readonly long _maxPixels;

    public NoteMediaProcessor(IOptions<MediaOptions> options)
    {
        _maxPixels = options.Value.MaxImagePixels;
    }

    /// <summary>Longest edge of the card-sized rendition built by <see cref="CreatePreviewAsync"/>.</summary>
    public const int PreviewEdge = 1280;

    /// <summary>
    /// Builds the card-sized rendition of a stored original. The thumbnail suits the editor's small
    /// tiles, but a note card shows its photo at close to screen width — some 1,000–1,400 px on a
    /// phone — where 400 px is visibly soft and the 2,560 px original is several times the bytes
    /// the card needs. Stored originals are already oriented and stripped, so this only resizes.
    /// They are also this service's own JPEGs of at most 2,560 px, so their decode is small and needs
    /// none of an upload's limits.
    /// </summary>
    /// <param name="original">A stored original.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The JPEG preview, positioned at its start.</returns>
    public async Task<MemoryStream> CreatePreviewAsync(Stream original, CancellationToken ct)
    {
        using var image = await Image.LoadAsync(original, ct);

        // Never upscale: ResizeMode.Max grows an image smaller than the box.
        if (image.Width > PreviewEdge || image.Height > PreviewEdge)
            image.Mutate(x => x.Resize(new ResizeOptions
            {
                Mode = ResizeMode.Max,
                Size = new Size(PreviewEdge, PreviewEdge),
            }));

        var preview = new MemoryStream();
        await image.SaveAsJpegAsync(preview, new JpegEncoder { Quality = JpegQuality }, ct);
        preview.Position = 0;
        return preview;
    }

    /// <summary>Validates and processes an upload, once one of the decode slots is free.</summary>
    /// <param name="upload">The uploaded content, read once from its start.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The processed image, or the rejection reason.</returns>
    public async Task<MediaProcessResult> ProcessAsync(Stream upload, CancellationToken ct)
    {
        await DecodeSlots.WaitAsync(ct);
        try
        {
            // Buffered only now, inside the slot: an upload still waiting holds nothing but its
            // request body, which ASP.NET Core keeps on disk beyond 64 KB.
            await using var buffer = new MemoryStream();
            await upload.CopyToAsync(buffer, ct);
            return await ProcessBufferedAsync(buffer, ct);
        }
        finally
        {
            DecodeSlots.Release();
        }
    }

    /// <summary>The checks and renditions of <see cref="ProcessAsync"/>, on a seekable copy.</summary>
    /// <param name="upload">The buffered upload.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The processed image, or the rejection reason.</returns>
    private async Task<MediaProcessResult> ProcessBufferedAsync(MemoryStream upload, CancellationToken ct)
    {
        if (await IsHeicAsync(upload, ct))
            return new MediaProcessResult(null, MediaRejection.Heic);

        upload.Position = 0;
        if (!await ImageService.LooksLikeImageAsync(upload, ct))
            return new MediaProcessResult(null, MediaRejection.NotAnImage);

        upload.Position = 0;

        try
        {
            // The header alone, without decoding: dimensions cost nothing to claim.
            var info = await Image.IdentifyAsync(Decoding, upload, ct);
            if ((long)info.Width * info.Height > _maxPixels)
                return new MediaProcessResult(null, MediaRejection.TooManyPixels);

            upload.Position = 0;
            using var image = await Image.LoadAsync(Decoding, upload, ct);

            // Orientation is applied here; AutoOrient plus stripping metadata below means a sideways
            // phone photo displays upright on every client with no client-side EXIF logic.
            image.Mutate(x => x.AutoOrient());
            image.Metadata.ExifProfile = null;
            image.Metadata.XmpProfile = null;
            image.Metadata.IptcProfile = null;

            // Animated GIFs would lose their frames through a resize/JPEG encode, so they are stored
            // as-is and thumbnailed from the first frame only.
            var isGif = image.Metadata.DecodedImageFormat is GifFormat;

            var original = new MemoryStream();
            string extension;

            if (isGif)
            {
                upload.Position = 0;
                await upload.CopyToAsync(original, ct);
                extension = ".gif";
            }
            else
            {
                if (image.Width > MaxOriginalEdge || image.Height > MaxOriginalEdge)
                    image.Mutate(x => x.Resize(new ResizeOptions
                    {
                        Mode = ResizeMode.Max,
                        Size = new Size(MaxOriginalEdge, MaxOriginalEdge),
                    }));

                await image.SaveAsJpegAsync(original, new JpegEncoder { Quality = JpegQuality }, ct);
                extension = ".jpg";
            }

            original.Position = 0;

            using var thumb = image.Clone(x => x.Resize(new ResizeOptions
            {
                Mode = ResizeMode.Max,
                Size = new Size(ThumbnailEdge, ThumbnailEdge),
            }));

            var thumbnail = new MemoryStream();
            await thumb.SaveAsJpegAsync(thumbnail, new JpegEncoder { Quality = JpegQuality }, ct);
            thumbnail.Position = 0;

            return new MediaProcessResult(
                new ProcessedImage(original, extension, image.Width, image.Height, thumbnail, ".jpg"),
                MediaRejection.None);
        }
        catch (Exception ex) when (ex is ImageFormatException or NotSupportedException)
        {
            return new MediaProcessResult(null, MediaRejection.Corrupt);
        }
    }

    /// <summary>
    /// True when the stream is an ISO-BMFF file with a HEIC brand. Detected only so the endpoint can
    /// refuse it <em>by name</em> — iPhone-on-Safari users hit this constantly, and a generic
    /// "unsupported file type" would baffle them.
    /// </summary>
    /// <param name="stream">The uploaded content.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>True when the header carries a HEIC brand.</returns>
    private static async Task<bool> IsHeicAsync(Stream stream, CancellationToken ct)
    {
        stream.Position = 0;
        var header = new byte[12];
        var read = await stream.ReadAtLeastAsync(header, header.Length, throwOnEndOfStream: false, ct);
        if (read < 12) return false;

        // Bytes 4..8 are "ftyp"; 8..12 carry the brand.
        if (header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') return false;

        var brand = System.Text.Encoding.ASCII.GetString(header, 8, 4);
        return brand is "heic" or "heix" or "hevc" or "heim" or "heis" or "mif1" or "msf1";
    }
}
