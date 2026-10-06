using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Lists.Dtos;

/// <summary>Rename, recolor and/or re-icon a list. A null field is left unchanged.</summary>
public class UpdateListDto
{
    [MaxLength(NoteLimits.ListName)]
    public string? Name { get; set; }

    [MaxLength(NoteLimits.Color)]
    public string? Color { get; set; }

    /// <summary>
    /// The new icon (see <see cref="ListIcon"/>). "" removes it — null can't, since null means
    /// "leave it as it is".
    /// </summary>
    [MaxLength(NoteLimits.ListIcon)]
    public string? Icon { get; set; }
}
