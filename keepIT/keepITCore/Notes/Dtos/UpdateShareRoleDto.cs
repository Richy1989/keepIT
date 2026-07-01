using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>Request to change an existing collaborator's role on a note.</summary>
public class UpdateShareRoleDto
{
    /// <summary>The collaborator's new role (viewer/editor).</summary>
    public NoteRole Role { get; set; }
}
