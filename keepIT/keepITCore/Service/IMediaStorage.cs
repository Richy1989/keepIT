namespace keepITCore.Service;

/// <summary>
/// Where note media bytes live. Controllers depend on this port rather than <c>System.IO</c> so the
/// disk implementation can later be swapped for S3/MinIO without touching a caller
/// (ARCHITECTURE.md "Profile images &amp; media").
/// </summary>
public interface IMediaStorage
{
    /// <summary>Writes one file for a note and returns the bytes written.</summary>
    /// <param name="ownerId">The note's owner, which decides the subtree.</param>
    /// <param name="noteId">The note the file belongs to.</param>
    /// <param name="fileName">The server-generated file name.</param>
    /// <param name="content">The bytes to write.</param>
    /// <param name="ct">Cancellation token.</param>
    /// <returns>The number of bytes written.</returns>
    Task<long> SaveAsync(Guid ownerId, Guid noteId, string fileName, Stream content, CancellationToken ct);

    /// <summary>Opens a stored file for reading, or null when it is missing.</summary>
    /// <param name="ownerId">The note's owner.</param>
    /// <param name="noteId">The note the file belongs to.</param>
    /// <param name="fileName">The stored file name.</param>
    /// <returns>A readable stream, or null.</returns>
    Stream? OpenRead(Guid ownerId, Guid noteId, string fileName);

    /// <summary>Best-effort delete of one stored file. Never throws for a missing file.</summary>
    /// <param name="ownerId">The note's owner.</param>
    /// <param name="noteId">The note the file belongs to.</param>
    /// <param name="fileName">The stored file name.</param>
    void Delete(Guid ownerId, Guid noteId, string fileName);

    /// <summary>Best-effort delete of a note's whole media folder.</summary>
    /// <param name="ownerId">The note's owner.</param>
    /// <param name="noteId">The note whose folder to remove.</param>
    void DeleteNote(Guid ownerId, Guid noteId);

    /// <summary>Every (owner, note) folder pair currently on disk — the orphan sweep's input.</summary>
    /// <returns>The pairs found under the data root.</returns>
    IReadOnlyList<(Guid OwnerId, Guid NoteId)> EnumerateNoteFolders();
}
