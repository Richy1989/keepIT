using System.ComponentModel.DataAnnotations;

namespace keepITCore.Lists.Dtos;

/// <summary>Rename and/or recolor a list. A null field is left unchanged.</summary>
public class UpdateListDto
{
    [MaxLength(100)]
    public string? Name { get; set; }

    [MaxLength(32)]
    public string? Color { get; set; }
}
