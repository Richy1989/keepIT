using System.Text.Json.Serialization;

namespace keepITCore.Data;

/// <summary>
/// A collaborator's permission on a shared note (ARCHITECTURE.md "Sharing / collaboration"). The
/// owner is implicit and always has full control; this role only ever describes a <see cref="NoteShare"/>.
/// The <see cref="JsonStringEnumConverter{TEnum}"/> makes it serialize as a name and describe as a
/// string enum in OpenAPI, so the generated TS client gets a <c>"Viewer" | "Editor"</c> union.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter<NoteRole>))]
public enum NoteRole
{
    /// <summary>Read-only: sees the note and its live updates but cannot change its content.</summary>
    Viewer = 0,

    /// <summary>Read + write: may edit body/checklist/color like the owner, but cannot delete,
    /// re-share, or change other collaborators' roles.</summary>
    Editor = 1,
}
