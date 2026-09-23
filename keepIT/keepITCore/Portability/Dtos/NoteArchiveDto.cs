using keepITCore.Lists.Dtos;
using keepITCore.Notes.Dtos;

namespace keepITCore.Portability.Dtos;

/// <summary>
/// <c>keepit-export.json</c> — the manifest at the root of an export archive.
/// <para>
/// The archive deliberately carries the <em>same</em> <see cref="NoteDto"/> and
/// <see cref="ListDto"/> shapes the API already serves, rather than a format of its own: those
/// types are the contract (generated into the TypeScript client, mirrored in the Android
/// <c>Dtos.kt</c>), and the Android offline cache already persists exactly this pair. One shape,
/// three producers, nothing new to keep in sync.
/// </para>
/// <para>
/// Fields on those DTOs that the server derives per caller — <see cref="NoteDto.IsOwner"/>,
/// <see cref="NoteDto.Role"/>, <see cref="NoteDto.CanEdit"/>, <see cref="NoteDto.IsShared"/>,
/// <see cref="ListDto.NoteCount"/> — are a snapshot of the exporting user's view. They are
/// informational and ignored on import.
/// </para>
/// </summary>
public class NoteArchiveDto
{
    /// <summary>
    /// The format version this build writes. Bumped when a change would stop an older importer
    /// reading the file correctly; an importer refuses anything newer than it understands.
    /// </summary>
    public const int CurrentSchemaVersion = 1;

    /// <summary>The archive format version (see <see cref="CurrentSchemaVersion"/>).</summary>
    public int SchemaVersion { get; set; } = CurrentSchemaVersion;

    /// <summary>When the archive was written.</summary>
    public DateTime ExportedAtUtc { get; set; }

    /// <summary>
    /// The server build that wrote it (see <c>AppVersion.Current</c>). Purely diagnostic: when an
    /// import fails, this says which server produced the file.
    /// </summary>
    public string AppVersion { get; set; } = "";

    /// <summary>The exporting user's lists, alphabetical.</summary>
    public List<ListDto> Lists { get; set; } = new();

    /// <summary>
    /// The notes the exporting user <em>owns</em>, oldest first — including archived and trashed
    /// ones, with the flags preserved, because a backup that silently drops the trash is not a
    /// backup. Notes merely shared <em>with</em> them are another user's data and are not exported.
    /// </summary>
    public List<NoteDto> Notes { get; set; } = new();
}
