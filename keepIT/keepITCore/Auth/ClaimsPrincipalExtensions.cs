using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;

namespace keepITCore.Auth;

/// <summary>Helpers for reading well-known values off the authenticated <see cref="ClaimsPrincipal"/>.</summary>
public static class ClaimsPrincipalExtensions
{
    /// <summary>Reads the authenticated user's id from the JWT "sub" claim (or NameIdentifier).</summary>
    /// <param name="principal">The current user principal.</param>
    /// <returns>The user's id, or <c>null</c> if the claim is missing or not a valid Guid.</returns>
    public static Guid? GetUserId(this ClaimsPrincipal principal)
    {
        var value = principal.FindFirstValue(JwtRegisteredClaimNames.Sub)
                    ?? principal.FindFirstValue(ClaimTypes.NameIdentifier);

        return Guid.TryParse(value, out var id) ? id : null;
    }
}
