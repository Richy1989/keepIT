namespace keepITCore.Auth;

/// <summary>Bound from the "Jwt" config section. Set <see cref="Key"/> via env (Jwt__Key) in prod.</summary>
public class JwtOptions
{
    public const string SectionName = "Jwt";

    public string Issuer { get; set; } = "defualt";
    public string Audience { get; set; } = "default";

    /// <summary>HMAC signing key. Must be long/random (>= 32 bytes). Never commit a real one.</summary>
    public string Key { get; set; } = "";

    public int AccessTokenMinutes { get; set; } = 15;
    public int RefreshTokenDays { get; set; } = 14;
}
