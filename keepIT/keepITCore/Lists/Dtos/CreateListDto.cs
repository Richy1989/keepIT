using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Lists.Dtos;

/// <summary>Payload to create a list.</summary>
public class CreateListDto
{
    [Required, MaxLength(NoteLimits.ListName)]
    public string Name { get; set; } = "";

    [MaxLength(NoteLimits.Color)]
    public string? Color { get; set; }

    /// <summary>One emoji or other single symbol (see <see cref="ListIcon"/>); null or "" for none.</summary>
    [MaxLength(NoteLimits.ListIcon)]
    public string? Icon { get; set; }
}
