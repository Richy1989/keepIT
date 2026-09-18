using keepITCore.Infrastructure;

namespace keepITCore.Service;

/// <summary>
/// <see cref="IMediaStorage"/> over the local data root. File names are always server-generated
/// (<c>{mediaId}.{ext}</c>), so nothing here interpolates client input into a path.
/// </summary>
public class DiskMediaStorage : IMediaStorage
{
    /// <inheritdoc />
    public async Task<long> SaveAsync(Guid ownerId, Guid noteId, string fileName, Stream content, CancellationToken ct)
    {
        var dir = FolderManagement.GetNoteMediaFolder(ownerId.ToString(), noteId.ToString());
        var path = Path.Combine(dir, fileName);

        // Written aside and moved into place, so a reader can never open a half-written file. That
        // matters since previews are generated on first request: a second client asking for the
        // same one mid-write would otherwise get truncated bytes — and cache them for good.
        var tmp = $"{path}.{Guid.NewGuid():N}.tmp";
        try
        {
            long length;
            await using (var file = File.Create(tmp))
            {
                await content.CopyToAsync(file, ct);
                length = file.Length;
            }

            File.Move(tmp, path, overwrite: true);
            return length;
        }
        catch
        {
            try { File.Delete(tmp); }
            catch (IOException) { /* best-effort; the note's folder goes with the note */ }
            throw;
        }
    }

    /// <inheritdoc />
    public Stream? OpenRead(Guid ownerId, Guid noteId, string fileName)
    {
        var path = Path.Combine(
            FolderManagement.GetNoteMediaFolder(ownerId.ToString(), noteId.ToString()), fileName);
        return File.Exists(path) ? File.OpenRead(path) : null;
    }

    /// <inheritdoc />
    public void Delete(Guid ownerId, Guid noteId, string fileName)
    {
        var path = Path.Combine(
            FolderManagement.GetNoteMediaFolder(ownerId.ToString(), noteId.ToString()), fileName);
        try { File.Delete(path); }
        catch (IOException) { /* best-effort; an orphan file is harmless and the sweep collects it */ }
    }

    /// <inheritdoc />
    public void DeleteNote(Guid ownerId, Guid noteId)
    {
        var dir = Path.Combine(FolderManagement.GetUserFolder(ownerId.ToString()), "notes", noteId.ToString());
        try { if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true); }
        catch (IOException) { /* best-effort */ }
    }

    /// <inheritdoc />
    public IReadOnlyList<(Guid OwnerId, Guid NoteId)> EnumerateNoteFolders()
    {
        var results = new List<(Guid, Guid)>();
        var usersRoot = Path.Combine(FolderManagement.RootPath, "users");
        if (!Directory.Exists(usersRoot)) return results;

        foreach (var userDir in Directory.EnumerateDirectories(usersRoot))
        {
            if (!Guid.TryParse(Path.GetFileName(userDir), out var ownerId)) continue;
            var notesDir = Path.Combine(userDir, "notes");
            if (!Directory.Exists(notesDir)) continue;

            foreach (var noteDir in Directory.EnumerateDirectories(notesDir))
                if (Guid.TryParse(Path.GetFileName(noteDir), out var noteId))
                    results.Add((ownerId, noteId));
        }

        return results;
    }
}
