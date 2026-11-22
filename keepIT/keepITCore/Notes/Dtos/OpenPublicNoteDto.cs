using System.ComponentModel.DataAnnotations;

namespace keepITCore.Notes.Dtos;

/// <summary>A visitor opening a public link: the token from the link's fragment, and the password if it has one.</summary>
public class OpenPublicNoteDto
{
    /// <summary>The token (the part of the link after <c>#</c>).</summary>
    [Required]
    [MaxLength(128)]
    public string Token { get; set; } = "";

    /// <summary>The link's password, when it has one.</summary>
    [MaxLength(128)]
    public string? Password { get; set; }
}
