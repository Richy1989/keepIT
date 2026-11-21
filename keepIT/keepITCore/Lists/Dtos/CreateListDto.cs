using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Lists.Dtos;

/// <summary>Payload to create a list.</summary>
public class CreateListDto
{
    /// <summary>
    /// The id the client picked for the list, so that sending the same create again (its answer was
    /// lost) returns the list it made instead of making a second one. Kept when no list has it; when
    /// the caller's own list has it, that list is the answer; when someone else's has it, the list
    /// gets a new id. Null: the server picks one.
    /// </summary>
    public Guid? Id { get; set; }

    [Required, MaxLength(NoteLimits.ListName)]
    public string Name { get; set; } = "";

    [MaxLength(NoteLimits.Color)]
    public string? Color { get; set; }

    /// <summary>One emoji or other single symbol (see <see cref="ListIcon"/>); null or "" for none.</summary>
    [MaxLength(NoteLimits.ListIcon)]
    public string? Icon { get; set; }
}
