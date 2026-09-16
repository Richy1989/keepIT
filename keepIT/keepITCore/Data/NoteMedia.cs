namespace keepITCore.Data;

/// <summary>
/// One image attached to a <see cref="Note"/>. Bytes live on disk under the data root (never in the
/// database — ARCHITECTURE.md "Profile images &amp; media"); this row is the metadata plus the
/// storage key. Media is owned <em>through</em> the note, so there is no separate owner column: the
/// note's <see cref="Note.OwnerId"/> decides both the on-disk folder and who may purge it.
/// <para>The collection is append-only, which is why media needs no conflict resolution: two
/// devices attaching at once both succeed and order by <see cref="CreatedAtUtc"/>.</para>
/// </summary>
public class NoteMedia
{
    /// <summary>The public id and the storage key — a client filename never reaches the disk.</summary>
    public Guid Id { get; set; }

    /// <summary>The note this image is attached to.</summary>
    public Guid NoteId { get; set; }

    /// <summary>Navigation to the owning note.</summary>
    public Note Note { get; set; } = null!;

    /// <summary>
    /// Stored file name, always <c>{Id}.{ext}</c>. Stored rather than derived so the response
    /// content type is one lookup.
    /// </summary>
    public string FileName { get; set; } = null!;

    /// <summary>
    /// Thumbnail file name, always <c>{Id}_thumb.{ext}</c>. Held separately because the thumbnail's
    /// encoding need not match the source (an animated GIF thumbnails to a still).
    /// </summary>
    public string ThumbFileName { get; set; } = null!;

    /// <summary>
    /// Pixel width of the stored original — lets a client reserve the card's box before the
    /// thumbnail arrives, so the notes grid doesn't reflow as images land.
    /// </summary>
    public int Width { get; set; }

    /// <summary>Pixel height of the stored original.</summary>
    public int Height { get; set; }

    /// <summary>
    /// Size on disk of the stored original, after re-encoding. Makes a future per-user quota a SUM
    /// rather than a migration.
    /// </summary>
    public long ByteSize { get; set; }

    /// <summary>
    /// Position within the note, ascending. Append-only: a new row takes <c>max(Order) + 1</c>, or 0
    /// when it is the first.
    /// </summary>
    public int Order { get; set; }

    /// <summary>Server-set attach time; the tie-break for concurrent attaches.</summary>
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
}
