using System.Text.Json.Serialization;

namespace keepITCore.Data;

/// <summary>
/// What kind of file one <see cref="NoteMedia"/> row holds. A note's attachments are one ordered
/// list whatever they are, so this is a discriminator on the existing row rather than a second
/// table: ordering, per-note limits, deletion, the realtime fan-out and the export archive all
/// carry over unchanged.
/// <para><b>Image must stay 0.</b> The column is appended to existing databases with the store
/// type's zero value (see <c>SqliteSchemaReconciler</c>), so every row that predates this enum has
/// to read back as what it actually is — an image.</para>
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter<NoteMediaKind>))]
public enum NoteMediaKind
{
    /// <summary>A picture: re-encoded on upload, with a generated thumbnail and a pixel size.</summary>
    Image = 0,

    /// <summary>
    /// A sound recording — a voice note. Stored exactly as uploaded (there is no audio encoder in
    /// the container), so it has no thumbnail and no pixel size, and carries a duration instead.
    /// </summary>
    Audio = 1,
}
