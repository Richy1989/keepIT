namespace keepITCore.Auth;

/// <summary>
/// Bound from "Auth:RefreshCookie". The refresh token rides in this httpOnly cookie so JS can't
/// read it.
/// </summary>
public class RefreshCookieOptions
{
    public const string SectionName = "Auth:RefreshCookie";

    public string Name { get; set; } = "keepit_refresh";

    /// <summary>
    /// True (default): the cookie is always Secure. False: Secure only when the request came over
    /// HTTPS, directly or through a TLS proxy, so an instance served over plain http (a LAN, local
    /// dev) still works. A request over HTTPS gets a Secure cookie either way.
    /// </summary>
    public bool Secure { get; set; } = true;

    /// <summary>Scope the cookie to the auth endpoints so it isn't sent on every API call.</summary>
    public string Path { get; set; } = "/api/auth";
}
