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

    /// <summary>How many images one note may hold.</summary>
    public int MaxImagesPerNote { get; set; } = 10;
}
