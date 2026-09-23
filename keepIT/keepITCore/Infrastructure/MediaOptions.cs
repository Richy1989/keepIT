namespace keepITCore.Infrastructure;

/// <summary>
/// Bound from the "App:Media" config section. Limits for note image attachments; there is
/// deliberately no per-user quota — registration is gated, and per-user usage is a SUM over the
/// media rows if one is ever wanted.
/// </summary>
public class MediaOptions
{
    public const string SectionName = "App:Media";

    /// <summary>Largest accepted upload, in bytes. Checked before any processing.</summary>
    public long MaxImageBytes { get; set; } = 10 * 1024 * 1024;

    /// <summary>
    /// Largest accepted voice note. Deliberately the same 10 MB as an image, because both travel
    /// through an <c>/api/</c> proxy capped at 12 MB — raising this alone would just move the
    /// refusal from the API, which explains itself, to nginx, which does not. At the bitrate the
    /// Android recorder uses (mono, ~32 kbps) it is roughly 40 minutes of speech.
    /// </summary>
    public long MaxAudioBytes { get; set; } = 10 * 1024 * 1024;

    /// <summary>How many voice notes one note may hold, counted separately from its images.</summary>
    public int MaxAudioPerNote { get; set; } = 10;

    /// <summary>How many images one note may hold.</summary>
    public int MaxImagesPerNote { get; set; } = 10;

    /// <summary>
    /// Largest image accepted, in pixels (width × height), read from the file's header before
    /// anything is decoded. Bytes don't bound it: a 2.6 MB PNG can declare 30,000 × 30,000 pixels
    /// and need gigabytes to decode. The default of 100 megapixels is above any phone photo that
    /// fits in <see cref="MaxImageBytes"/>; a server with little memory can go lower.
    /// </summary>
    public long MaxImagePixels { get; set; } = 100_000_000;
}
