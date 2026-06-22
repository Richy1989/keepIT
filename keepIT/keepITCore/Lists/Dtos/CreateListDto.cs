using System.ComponentModel.DataAnnotations;

namespace keepITCore.Lists.Dtos;

/// <summary>Payload to create a list.</summary>
public class CreateListDto
{
    [Required, MaxLength(100)]
    public string Name { get; set; } = "";

    [MaxLength(32)]
    public string? Color { get; set; }
}
