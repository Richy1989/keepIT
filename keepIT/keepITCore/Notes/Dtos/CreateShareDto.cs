using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>Request to invite a user to collaborate on a note: who (by email) and at what role.</summary>
public class CreateShareDto
{
    /// <summary>The recipient's email. Must be an existing user.</summary>
    [Required]
    [EmailAddress]
    public string Email { get; set; } = "";

    /// <summary>The role to offer (viewer/editor). Defaults to viewer.</summary>
    public NoteRole Role { get; set; } = NoteRole.Viewer;
}
