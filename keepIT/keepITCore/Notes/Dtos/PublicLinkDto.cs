namespace keepITCore.Notes.Dtos;

/// <summary>
/// A note's public link, as its owner sees it (ARCHITECTURE.md "Public links"). Only ever sent to
/// the owner: the token opens the note for anyone who has it.
/// </summary>
public class PublicLinkDto
{
    /// <summary>
    /// The token. The link is <c>{the address keepIT is opened at}/p#{token}</c>: in the fragment,
    /// which a browser never sends to a server, so it never reaches a log.
    /// </summary>
    public string Token { get; set; } = "";

    /// <summary>The whole link, when the server knows its public address (<c>App:PublicBaseUrl</c>); else null, and the app builds it.</summary>
    public string? Url { get; set; }

    /// <summary>True when visitors must enter a password.</summary>
    public bool HasPassword { get; set; }

    /// <summary>True when visitors may change the note's title, text and checklist.</summary>
    public bool AllowEdit { get; set; }

    /// <summary>When the link stops working; null for never.</summary>
    public DateTime? ExpiresAtUtc { get; set; }

    /// <summary>True once <see cref="ExpiresAtUtc"/> has passed: the link exists but opens nothing.</summary>
    public bool IsExpired { get; set; }

    public DateTime CreatedAtUtc { get; set; }
}
