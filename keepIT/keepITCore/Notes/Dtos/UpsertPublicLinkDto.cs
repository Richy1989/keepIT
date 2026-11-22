using System.ComponentModel.DataAnnotations;

namespace keepITCore.Notes.Dtos;

/// <summary>
/// Publishes a note, or changes how it is published. Every setting is sent each time except the
/// password, which the server never hands back: <see cref="Password"/> sets a new one,
/// <see cref="ClearPassword"/> removes it, and neither keeps the one there is.
/// </summary>
public class UpsertPublicLinkDto
{
    /// <summary>A new password visitors must enter; null keeps the current one (or none).</summary>
    [MinLength(4)]
    [MaxLength(128)]
    public string? Password { get; set; }

    /// <summary>True to remove the password. Ignored when <see cref="Password"/> is given.</summary>
    public bool ClearPassword { get; set; }

    /// <summary>When the link stops working; null for never. Must be in the future.</summary>
    public DateTime? ExpiresAtUtc { get; set; }

    /// <summary>True to let visitors change the note's title, text and checklist.</summary>
    public bool AllowEdit { get; set; }

    /// <summary>True to replace the token, so the old link stops working at once.</summary>
    public bool Regenerate { get; set; }
}
