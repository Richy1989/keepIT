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

    /// <summary>When the share was granted (or the invite sent, when <see cref="Pending"/>).</summary>
    public DateTime CreatedAtUtc { get; set; }

    /// <summary>True while this is an unanswered invite (no access yet); false for an accepted share.</summary>
    public bool Pending { get; set; }
}
