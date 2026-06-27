using System.IdentityModel.Tokens.Jwt;
using System.Text;
using keepITCore.Data;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Identity;
using Microsoft.IdentityModel.Tokens;

namespace keepITCore.Auth;

/// <summary>
/// Registers the authentication stack — establishing <em>who the caller is</em>: ASP.NET Core
/// Identity for user/password management, and JWT bearer validation for incoming requests. Kept out
/// of Program.cs so the startup file stays a readable outline; this is a different concern from the
/// edge abuse-protection in <c>SecurityServiceExtensions</c>.
/// </summary>
public static class AuthenticationServiceExtensions
{
    /// <summary>
    /// Adds Identity Core for user and password management. No cookie sign-in is configured — the
    /// app is JWT-only — so this registers just the user store, roles, and token providers.
    /// </summary>
    public static IServiceCollection AddAppIdentity(this IServiceCollection services)
    {
        services
            .AddIdentityCore<ApplicationUser>(options =>
            {
                options.User.RequireUniqueEmail = true;
                options.Password.RequiredLength = 8;
            })
            .AddRoles<IdentityRole<Guid>>()
            .AddEntityFrameworkStores<AppDbContext>()
            .AddDefaultTokenProviders();

        return services;
    }

    /// <summary>
    /// Configures JWT bearer authentication and authorization. Validates the issuer, audience,
    /// signing key, and lifetime, and keeps the raw <c>sub</c> claim (no inbound claim remapping) so
    /// it can be read back as the user id.
    /// </summary>
    /// <param name="services">The service collection.</param>
    /// <param name="jwtOptions">The bound JWT options (issuer, audience, signing key).</param>
    public static IServiceCollection AddJwtBearerAuthentication(this IServiceCollection services, JwtOptions jwtOptions)
    {
        JwtSecurityTokenHandler.DefaultMapInboundClaims = false; // keep "sub" instead of remapping it

        services
            .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(options =>
            {
                options.MapInboundClaims = false;
                options.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidIssuer = jwtOptions.Issuer,
                    ValidateAudience = true,
                    ValidAudience = jwtOptions.Audience,
                    ValidateIssuerSigningKey = true,
                    IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtOptions.Key)),
                    ValidateLifetime = true,
                    ClockSkew = TimeSpan.FromSeconds(30),
                    NameClaimType = JwtRegisteredClaimNames.Sub,
                };
            });

        services.AddAuthorization();

        return services;
    }
}
