using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using keepITCore.Data;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace keepITCore.Auth;

public interface ITokenService
{
    /// <summary>Builds a signed JWT access token for the user and returns it with its UTC expiry.</summary>
    (string token, DateTime expiresAtUtc) CreateAccessToken(ApplicationUser user);

    /// <summary>Generates a new opaque refresh token (the raw value) plus its storage hash.</summary>
    (string rawToken, string tokenHash, DateTime expiresAtUtc) CreateRefreshToken();

    /// <summary>Hashes a raw refresh token the same way it's stored, for lookup/comparison.</summary>
    string HashRefreshToken(string rawToken);
}

public class TokenService : ITokenService
{
    private readonly JwtOptions _options;
    private readonly SymmetricSecurityKey _signingKey;

    /// <summary>Caches the JWT options and builds the symmetric signing key from the configured secret.</summary>
    /// <param name="options">The bound <see cref="JwtOptions"/> (issuer, audience, key, lifetimes).</param>
    public TokenService(IOptions<JwtOptions> options)
    {
        _options = options.Value;
        _signingKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_options.Key));
    }

    /// <inheritdoc />
    public (string token, DateTime expiresAtUtc) CreateAccessToken(ApplicationUser user)
    {
        var expires = DateTime.UtcNow.AddMinutes(_options.AccessTokenMinutes);

        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, user.Id.ToString()),
            new(JwtRegisteredClaimNames.Email, user.Email ?? string.Empty),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
        };
        if (!string.IsNullOrEmpty(user.DisplayName))
            claims.Add(new Claim("name", user.DisplayName));

        var creds = new SigningCredentials(_signingKey, SecurityAlgorithms.HmacSha256);
        var jwt = new JwtSecurityToken(
            issuer: _options.Issuer,
            audience: _options.Audience,
            claims: claims,
            notBefore: DateTime.UtcNow,
            expires: expires,
            signingCredentials: creds);

        var token = new JwtSecurityTokenHandler().WriteToken(jwt);
        return (token, expires);
    }

    /// <inheritdoc />
    public (string rawToken, string tokenHash, DateTime expiresAtUtc) CreateRefreshToken()
    {
        var bytes = RandomNumberGenerator.GetBytes(32);
        var rawToken = Base64UrlEncode(bytes);
        var expires = DateTime.UtcNow.AddDays(_options.RefreshTokenDays);
        return (rawToken, HashRefreshToken(rawToken), expires);
    }

    /// <inheritdoc />
    public string HashRefreshToken(string rawToken)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(rawToken));
        return Convert.ToBase64String(hash);
    }

    /// <summary>Encodes bytes as URL-safe Base64 (no padding) for use in a cookie value.</summary>
    /// <param name="bytes">The raw bytes to encode.</param>
    /// <returns>The URL-safe Base64 representation.</returns>
    private static string Base64UrlEncode(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
