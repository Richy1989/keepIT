namespace keepITCore.Notes.Dtos;

/// <summary>
/// Someone else on a shared note, as the caller's note card shows them: a round picture, or the
/// first letter of their email when they have none. Only what a collaborator could already see in
/// the note's share list (<see cref="NoteShareDto"/>) and through <c>getProfileImage</c>.
/// </summary>
public class NoteCollaboratorDto
{
    /// <summary>The user's id, which their profile picture is fetched by.</summary>
    public Guid UserId { get; set; }

    /// <summary>The user's email, for display.</summary>
    public string Email { get; set; } = "";

    /// <summary>True for the note's owner, false for a user it is shared with.</summary>
    public bool IsOwner { get; set; }

    /// <summary>
    /// Changes with every new profile picture, so a client can keep one picture per user and
    /// version and fetch again only when it changes; null when the user has no picture, so a client
    /// need not ask for one.
    /// </summary>
    public string? ProfileImageVersion { get; set; }
}
