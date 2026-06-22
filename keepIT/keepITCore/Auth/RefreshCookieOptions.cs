namespace keepITCore.Auth;

/// <summary>
/// Bound from "Auth:RefreshCookie". The refresh token rides in this httpOnly cookie so JS can't
/// read it. <see cref="Secure"/> defaults to true; set it false in local dev when serving over
/// plain http (otherwise the browser won't send the cookie).
/// </summary>
public class RefreshCookieOptions
{
    public const string SectionName = "Auth:RefreshCookie";

    public string Name { get; set; } = "keepit_refresh";
    public bool Secure { get; set; } = true;

    /// <summary>Scope the cookie to the auth endpoints so it isn't sent on every API call.</summary>
    public string Path { get; set; } = "/api/auth";
}
