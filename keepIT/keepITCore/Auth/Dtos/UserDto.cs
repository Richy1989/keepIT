namespace keepITCore.Auth.Dtos;

/// <summary>The authenticated user as exposed to the client. Never includes secrets.</summary>
public class UserDto
{
    public Guid Id { get; set; }
    public string Email { get; set; } = null!;
    public string? DisplayName { get; set; }
}
