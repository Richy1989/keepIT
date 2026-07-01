using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>One collaborator on a note: who they are and their role. Returned by the collaborators list.</summary>
public class NoteShareDto
{
    /// <summary>The collaborator's user id.</summary>
    public Guid GranteeId { get; set; }

    /// <summary>The collaborator's email, for display.</summary>
    public string Email { get; set; } = "";

    /// <summary>The collaborator's role (viewer/editor).</summary>
    public NoteRole Role { get; set; }

    /// <summary>When the share was granted.</summary>
    public DateTime CreatedAtUtc { get; set; }
}
