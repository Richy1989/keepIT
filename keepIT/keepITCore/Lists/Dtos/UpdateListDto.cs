using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Lists.Dtos;

/// <summary>Rename and/or recolor a list. A null field is left unchanged.</summary>
public class UpdateListDto
{
    [MaxLength(NoteLimits.ListName)]
    public string? Name { get; set; }

    [MaxLength(NoteLimits.Color)]
    public string? Color { get; set; }
}
