namespace keepITCore.Auth.Dtos;

/// <summary>
/// Returned by register/login/refresh. The access token is meant to be held in memory by the
/// client; the refresh token is NOT in this body — it's set as an httpOnly cookie.
/// </summary>
public class AuthResponseDto
{
    public string AccessToken { get; set; } = null!;

    /// <summary>UTC expiry of the access token, so the client can schedule a silent refresh.</summary>
    public DateTime AccessTokenExpiresAtUtc { get; set; }

    public UserDto User { get; set; } = null!;
}
