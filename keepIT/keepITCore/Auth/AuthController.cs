using keepITCore.Auth.Dtos;
using keepITCore.Data;
using keepITCore.Infrastructure.Security;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace keepITCore.Auth;

/// <summary>
/// Authentication endpoints: register, login, refresh, logout, and the current-user lookup.
/// Issues a short-lived JWT access token (response body) and a rotating refresh token (httpOnly cookie).
/// </summary>
[ApiController]
[Route("api/auth")]
[EnableRateLimiting(RateLimitPolicies.Auth)]
public class AuthController : ControllerBase
{
    private readonly UserManager<ApplicationUser> _userManager;
    private readonly ITokenService _tokenService;
    private readonly AppDbContext _db;
    private readonly RefreshCookieOptions _cookieOptions;

    /// <summary>Injects Identity's user manager, the token service, the DB context, and cookie options.</summary>
    /// <param name="userManager">Identity user store for create/find/password checks.</param>
    /// <param name="tokenService">Mints access tokens and opaque refresh tokens.</param>
    /// <param name="db">Database context, used here for refresh-token persistence.</param>
    /// <param name="cookieOptions">Refresh-cookie settings (name, secure flag, path).</param>
    public AuthController(
        UserManager<ApplicationUser> userManager,
        ITokenService tokenService,
        AppDbContext db,
        IOptions<RefreshCookieOptions> cookieOptions)
    {
        _userManager = userManager;
        _tokenService = tokenService;
        _db = db;
        _cookieOptions = cookieOptions.Value;
    }

    /// <summary>Create an account. Returns an access token and sets the refresh cookie.</summary>
    /// <param name="dto">The new account's email, password, and optional display name.</param>
    /// <returns>200 with the auth payload, 409 if the email is taken, or 400 on validation errors.</returns>
    [HttpPost("register")]
    [AllowAnonymous]
    public async Task<ActionResult<AuthResponseDto>> Register(RegisterRequestDto dto)
    {
        if (await _userManager.FindByEmailAsync(dto.Email) is not null)
            return Conflict(new { error = "An account with this email already exists." });

        var user = new ApplicationUser
        {
            UserName = dto.Email,
            Email = dto.Email,
            DisplayName = dto.DisplayName,
        };

        var result = await _userManager.CreateAsync(user, dto.Password);
        if (!result.Succeeded)
        {
            foreach (var e in result.Errors)
                ModelState.AddModelError(e.Code, e.Description);
            return ValidationProblem(ModelState);
        }

        return await IssueTokensAsync(user);
    }

    /// <summary>
    /// Change the signed-in user's password. On success, revokes all of the user's existing refresh
    /// tokens (signing out other devices) and issues a fresh access token + refresh cookie so the
    /// current device stays signed in.
    /// </summary>
    /// <param name="dto">The current and new passwords. The DTO's UserId is ignored; the caller is
    /// taken from the access token.</param>
    /// <returns>200 with a new auth payload, 401 if unauthenticated, or 400 if the current password
    /// is wrong or the new one fails the complexity rules.</returns>
    [HttpPost("changepassword")]
    [Authorize]
    public async Task<ActionResult<AuthResponseDto>> ChangePassword(ChangePasswordRequestDto dto)
    {
        var userId = User.GetUserId();
        if (userId is null)
            return Unauthorized();

        var applicationUser = await _userManager.FindByIdAsync(userId.Value.ToString());
        if (applicationUser is null)
            return Unauthorized();

        var result = await _userManager.ChangePasswordAsync(applicationUser, dto.CurrentPassword, dto.NewPassword);
        if (!result.Succeeded)
        {
            foreach (var e in result.Errors)
                ModelState.AddModelError(e.Code, e.Description);
            return ValidationProblem(ModelState);
        }

        // Invalidate every existing session: revoke all of the user's active refresh tokens. Then
        // IssueTokensAsync mints a new one for this device, so the caller stays signed in.
        var now = DateTime.UtcNow;
        await _db.RefreshTokens
            .Where(rt => rt.UserId == applicationUser.Id && rt.RevokedAtUtc == null)
            .ExecuteUpdateAsync(s => s.SetProperty(rt => rt.RevokedAtUtc, now));

        return await IssueTokensAsync(applicationUser);
    }

    /// <summary>
    /// Exchange credentials for an access token + refresh cookie. Failed attempts count toward the
    /// per-account lockout (see <c>AddAppIdentity</c>); a locked account gets the same generic 401 as
    /// bad credentials so the response never reveals whether an email exists or is locked.
    /// </summary>
    /// <param name="dto">The login email and password.</param>
    /// <returns>200 with the auth payload, or 401 if the credentials are invalid or the account is locked.</returns>
    [HttpPost("login")]
    [AllowAnonymous]
    public async Task<ActionResult<AuthResponseDto>> Login(LoginRequestDto dto)
    {
        var user = await _userManager.FindByEmailAsync(dto.Email);
        if (user is null)
            return Unauthorized(new { error = "Invalid email or password." });

        if (await _userManager.IsLockedOutAsync(user))
            return Unauthorized(new { error = "Invalid email or password." });

        if (!await _userManager.CheckPasswordAsync(user, dto.Password))
        {
            // Count the failure; Identity trips the lockout automatically at the configured max.
            await _userManager.AccessFailedAsync(user);
            return Unauthorized(new { error = "Invalid email or password." });
        }

        // Successful sign-in clears the failure counter.
        await _userManager.ResetAccessFailedCountAsync(user);

        return await IssueTokensAsync(user);
    }

    /// <summary>
    /// Rotate the refresh cookie and mint a fresh access token. No access token required.
    /// <para><b>Reuse detection:</b> presenting a token that was already rotated/revoked is the
    /// signature of a stolen cookie being replayed (the legitimate client holds the newer token).
    /// When that happens every active session for the user is revoked, so both the attacker and the
    /// real user must sign in again — cutting off whoever only holds the copied token.</para>
    /// </summary>
    /// <returns>200 with a new auth payload, or 401 if the refresh cookie is missing/expired/revoked.</returns>
    [HttpPost("refresh")]
    [AllowAnonymous]
    public async Task<ActionResult<AuthResponseDto>> Refresh()
    {
        if (!Request.Cookies.TryGetValue(_cookieOptions.Name, out var rawToken) || string.IsNullOrEmpty(rawToken))
            return Unauthorized(new { error = "No refresh token." });

        var hash = _tokenService.HashRefreshToken(rawToken);
        var stored = await _db.RefreshTokens
            .Include(rt => rt.User)
            .FirstOrDefaultAsync(rt => rt.TokenHash == hash);

        if (stored is null)
        {
            ClearRefreshCookie();
            return Unauthorized(new { error = "Invalid or expired refresh token." });
        }

        if (!stored.IsActive)
        {
            // A known-but-revoked token was replayed: assume theft and kill the whole family.
            if (stored.RevokedAtUtc is not null && !stored.IsExpired)
            {
                var now = DateTime.UtcNow;
                await _db.RefreshTokens
                    .Where(rt => rt.UserId == stored.UserId && rt.RevokedAtUtc == null)
                    .ExecuteUpdateAsync(s => s.SetProperty(rt => rt.RevokedAtUtc, now));
            }
            ClearRefreshCookie();
            return Unauthorized(new { error = "Invalid or expired refresh token." });
        }

        // Rotate: revoke the presented token and issue a new one in its place.
        var (raw, newHash, expiresAt) = _tokenService.CreateRefreshToken();
        stored.RevokedAtUtc = DateTime.UtcNow;
        stored.ReplacedByTokenHash = newHash;

        _db.RefreshTokens.Add(new RefreshToken
        {
            Id = Guid.NewGuid(),
            UserId = stored.UserId,
            TokenHash = newHash,
            ExpiresAtUtc = expiresAt,
        });
        await _db.SaveChangesAsync();

        // Housekeeping: drop this user's expired rows. Revoked-but-unexpired rows are kept on
        // purpose — they're what makes the replay detection above possible.
        await _db.RefreshTokens
            .Where(rt => rt.UserId == stored.UserId && rt.ExpiresAtUtc < DateTime.UtcNow)
            .ExecuteDeleteAsync();

        SetRefreshCookie(raw, expiresAt);

        var (accessToken, accessExpires) = _tokenService.CreateAccessToken(stored.User);
        return Ok(BuildResponse(stored.User, accessToken, accessExpires));
    }

    /// <summary>Revoke the current refresh token and clear the cookie.</summary>
    /// <returns>204 No Content. Idempotent — succeeds even without a valid cookie.</returns>
    [HttpPost("logout")]
    [AllowAnonymous]
    public async Task<IActionResult> Logout()
    {
        if (Request.Cookies.TryGetValue(_cookieOptions.Name, out var rawToken) && !string.IsNullOrEmpty(rawToken))
        {
            var hash = _tokenService.HashRefreshToken(rawToken);
            var stored = await _db.RefreshTokens.FirstOrDefaultAsync(rt => rt.TokenHash == hash);
            if (stored is not null && stored.RevokedAtUtc is null)
            {
                stored.RevokedAtUtc = DateTime.UtcNow;
                await _db.SaveChangesAsync();
            }
        }

        ClearRefreshCookie();
        return NoContent();
    }

    /// <summary>The current user. Requires a valid access token.</summary>
    /// <returns>200 with the user, or 401 if the token is missing/invalid or the user no longer exists.</returns>
    [HttpGet("me")]
    [Authorize]
    public async Task<ActionResult<UserDto>> Me()
    {
        var userId = User.GetUserId();
        if (userId is null)
            return Unauthorized();

        var user = await _userManager.FindByIdAsync(userId.Value.ToString());
        if (user is null)
            return Unauthorized();

        return Ok(ToUserDto(user));
    }

    // ---- helpers ----

    /// <summary>
    /// Issues a new refresh token (persisted) and access token for the user, sets the refresh
    /// cookie, and returns the auth payload. Shared by register and login.
    /// </summary>
    /// <param name="user">The authenticated user to issue tokens for.</param>
    /// <returns>200 with the access token and user details.</returns>
    private async Task<ActionResult<AuthResponseDto>> IssueTokensAsync(ApplicationUser user)
    {
        var (raw, hash, refreshExpires) = _tokenService.CreateRefreshToken();
        _db.RefreshTokens.Add(new RefreshToken
        {
            Id = Guid.NewGuid(),
            UserId = user.Id,
            TokenHash = hash,
            ExpiresAtUtc = refreshExpires,
        });
        await _db.SaveChangesAsync();

        SetRefreshCookie(raw, refreshExpires);

        var (accessToken, accessExpires) = _tokenService.CreateAccessToken(user);
        return Ok(BuildResponse(user, accessToken, accessExpires));
    }

    /// <summary>Assembles the response DTO from a user and a freshly minted access token.</summary>
    /// <param name="user">The authenticated user.</param>
    /// <param name="accessToken">The signed JWT access token.</param>
    /// <param name="accessExpires">The access token's UTC expiry.</param>
    /// <returns>The auth payload returned to the client.</returns>
    private static AuthResponseDto BuildResponse(ApplicationUser user, string accessToken, DateTime accessExpires) =>
        new()
        {
            AccessToken = accessToken,
            AccessTokenExpiresAtUtc = accessExpires,
            User = ToUserDto(user),
        };

    /// <summary>Projects an <see cref="ApplicationUser"/> to the client-safe <see cref="UserDto"/>.</summary>
    /// <param name="user">The user entity.</param>
    /// <returns>A DTO with only the non-secret fields.</returns>
    private static UserDto ToUserDto(ApplicationUser user) =>
        new()
        {
            Id = user.Id,
            Email = user.Email!,
            DisplayName = user.DisplayName,
        };

    /// <summary>Writes the refresh token to the httpOnly cookie (Secure/SameSite/Path from options).</summary>
    /// <param name="rawToken">The opaque refresh token value to store in the cookie.</param>
    /// <param name="expiresAtUtc">The cookie's expiry, matching the refresh token's lifetime.</param>
    private void SetRefreshCookie(string rawToken, DateTime expiresAtUtc) =>
        Response.Cookies.Append(_cookieOptions.Name, rawToken, new CookieOptions
        {
            HttpOnly = true,
            Secure = _cookieOptions.Secure,
            SameSite = SameSiteMode.Strict,
            Path = _cookieOptions.Path,
            Expires = expiresAtUtc,
        });

    /// <summary>Deletes the refresh cookie, using matching options so the browser actually removes it.</summary>
    private void ClearRefreshCookie() =>
        Response.Cookies.Delete(_cookieOptions.Name, new CookieOptions
        {
            HttpOnly = true,
            Secure = _cookieOptions.Secure,
            SameSite = SameSiteMode.Strict,
            Path = _cookieOptions.Path,
        });
}
