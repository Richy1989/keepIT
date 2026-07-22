using keepITCore.Auth.Dtos;
using keepITCore.Data;
using keepITCore.Infrastructure.Email;
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
public class AuthController : ControllerBase
{
    /// <summary>
    /// How long a rotated refresh token remains acceptable after its replacement was issued.
    /// A token replayed within this window is almost always our own lost response — a page reload
    /// aborting an in-flight refresh, or two tabs racing on the shared cookie — not theft, so we
    /// rotate again instead of revoking the whole family. Outside the window, replay is treated as
    /// a stolen cookie (see <see cref="Refresh"/>).
    /// </summary>
    private static readonly TimeSpan RotationGraceWindow = TimeSpan.FromSeconds(60);

    private readonly UserManager<ApplicationUser> _userManager;
    private readonly ITokenService _tokenService;
    private readonly AppDbContext _db;
    private readonly RefreshCookieOptions _cookieOptions;
    private readonly IConfiguration _config;
    private readonly IEmailSender _emailSender;
    private readonly ILogger<AuthController> _logger;

    /// <summary>Injects Identity's user manager, the token service, the DB context, cookie options, config, and email.</summary>
    /// <param name="userManager">Identity user store for create/find/password checks.</param>
    /// <param name="tokenService">Mints access tokens and opaque refresh tokens.</param>
    /// <param name="db">Database context, used here for refresh-token persistence.</param>
    /// <param name="cookieOptions">Refresh-cookie settings (name, secure flag, path).</param>
    /// <param name="config">App configuration, read for <c>App:AllowRegistration</c> and <c>App:PublicBaseUrl</c>.</param>
    /// <param name="emailSender">Delivers the password-reset link (SMTP, or the server log when unconfigured).</param>
    /// <param name="logger">Controller logger.</param>
    public AuthController(
        UserManager<ApplicationUser> userManager,
        ITokenService tokenService,
        AppDbContext db,
        IOptions<RefreshCookieOptions> cookieOptions,
        IConfiguration config,
        IEmailSender emailSender,
        ILogger<AuthController> logger)
    {
        _userManager = userManager;
        _tokenService = tokenService;
        _db = db;
        _cookieOptions = cookieOptions.Value;
        _config = config;
        _emailSender = emailSender;
        _logger = logger;
    }

    /// <summary>
    /// Create an account. Returns an access token and sets the refresh cookie.
    /// <para>Sign-up can be switched off via <c>App:AllowRegistration=false</c> — the intended mode
    /// for a personal instance exposed to the internet: create your accounts first, then close the
    /// door. Existing users are unaffected; only new registrations are refused.</para>
    /// </summary>
    /// <param name="dto">The new account's email, password, and optional display name.</param>
    /// <returns>200 with the auth payload, 403 when registration is disabled, 409 if the email is
    /// taken, or 400 on validation errors.</returns>
    [HttpPost("register")]
    [AllowAnonymous]
    [EnableRateLimiting(RateLimitPolicies.Auth)]
    public async Task<ActionResult<AuthResponseDto>> Register(RegisterRequestDto dto)
    {
        if (!(_config.GetValue<bool?>("App:AllowRegistration") ?? true))
            return StatusCode(StatusCodes.Status403Forbidden,
                new { error = "Registration is disabled on this server." });

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
    /// <param name="dto">The current and new passwords. The caller is taken from the access token.</param>
    /// <returns>200 with a new auth payload, 401 if unauthenticated, or 400 if the current password
    /// is wrong or the new one fails the complexity rules.</returns>
    [HttpPost("changepassword")]
    [Authorize]
    [EnableRateLimiting(RateLimitPolicies.Auth)]
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
    /// Request a password-reset link. <b>Always returns 204</b>, whether or not an account exists
    /// for the address — the response must not reveal which emails are registered (same
    /// non-enumeration stance as login's generic 401). When the account exists, a single-use,
    /// time-limited reset token is generated and the link is delivered via <see cref="IEmailSender"/>
    /// — SMTP when configured, otherwise the server log (self-hosted operators own the logs).
    /// </summary>
    /// <param name="dto">The account email to send the reset link to.</param>
    /// <returns>204 No Content, always (or 400 on validation errors).</returns>
    [HttpPost("forgot-password")]
    [AllowAnonymous]
    [EnableRateLimiting(RateLimitPolicies.Auth)]
    public async Task<IActionResult> ForgotPassword(ForgotPasswordRequestDto dto)
    {
        var user = await _userManager.FindByEmailAsync(dto.Email);
        if (user is not null)
        {
            var token = await _userManager.GeneratePasswordResetTokenAsync(user);
            var link = $"{PublicBaseUrl()}/reset-password" +
                       $"?email={Uri.EscapeDataString(user.Email!)}&token={Uri.EscapeDataString(token)}";

            try
            {
                await _emailSender.SendAsync(
                    user.Email!,
                    "Reset your keepIT password",
                    "Someone (hopefully you) requested a password reset for your keepIT account.\n\n" +
                    $"Open this link to choose a new password:\n{link}\n\n" +
                    "The link is valid for 2 hours and can be used once. If you didn't request " +
                    "this, you can safely ignore this email — your password is unchanged.");
            }
            catch (Exception ex)
            {
                // Swallow delivery failures: a 500 only for existing accounts would leak which
                // emails are registered. The operator sees the problem here in the log.
                _logger.LogError(ex, "Failed to deliver password-reset email");
            }
        }

        return NoContent();
    }

    /// <summary>
    /// Complete a password reset with the token from the emailed link. On success, clears any
    /// lockout and revokes <b>all</b> of the user's refresh tokens (the reset was triggered because
    /// the account may be compromised or the password lost — every existing session must die). The
    /// user signs in again with the new password; no tokens are issued here.
    /// </summary>
    /// <param name="dto">The email + reset token from the link, and the new password.</param>
    /// <returns>204 on success, 400 with a generic error for an invalid/expired token (no account
    /// enumeration), or 400 with details when the new password fails the complexity rules.</returns>
    [HttpPost("reset-password")]
    [AllowAnonymous]
    [EnableRateLimiting(RateLimitPolicies.Auth)]
    public async Task<IActionResult> ResetPassword(ResetPasswordRequestDto dto)
    {
        // Unknown email gets the same generic error as a bad token: the link as a whole is invalid.
        var user = await _userManager.FindByEmailAsync(dto.Email);
        if (user is null)
            return BadRequest(new { error = "This reset link is invalid or has expired." });

        var result = await _userManager.ResetPasswordAsync(user, dto.Token, dto.NewPassword);
        if (!result.Succeeded)
        {
            // A bad/expired/reused token stays generic; password-rule failures are surfaced so the
            // user can actually fix them (they've already proven control of the email at this point).
            if (result.Errors.All(e => e.Code == nameof(IdentityErrorDescriber.InvalidToken)))
                return BadRequest(new { error = "This reset link is invalid or has expired." });

            foreach (var e in result.Errors)
                ModelState.AddModelError(e.Code, e.Description);
            return ValidationProblem(ModelState);
        }

        // Fresh start: a successful reset proves control of the email, so clear any lockout state
        // (an attacker may have caused it by guessing) …
        await _userManager.ResetAccessFailedCountAsync(user);
        await _userManager.SetLockoutEndDateAsync(user, null);

        // … and sign out every device, mirroring ChangePassword.
        var now = DateTime.UtcNow;
        await _db.RefreshTokens
            .Where(rt => rt.UserId == user.Id && rt.RevokedAtUtc == null)
            .ExecuteUpdateAsync(s => s.SetProperty(rt => rt.RevokedAtUtc, now));

        return NoContent();
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
    [EnableRateLimiting(RateLimitPolicies.Auth)]
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
    /// <para><b>Rotation grace:</b> a replay within <see cref="RotationGraceWindow"/> of the
    /// rotation is exempt — that's the browser losing the rotation response (a reload aborting the
    /// in-flight refresh, or two tabs racing on the shared cookie), not an attacker who sat on a
    /// stolen cookie. Those callers get a fresh sibling token instead of a family-wide revoke.</para>
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
            // Rotated moments ago? That's our own lost response, not theft — fall through and
            // rotate again. (Logout revokes without setting ReplacedByTokenHash, so a logged-out
            // token never qualifies.)
            var isRecentRotation = stored.ReplacedByTokenHash is not null
                && stored.RevokedAtUtc is not null
                && DateTime.UtcNow - stored.RevokedAtUtc.Value <= RotationGraceWindow
                && !stored.IsExpired;

            if (!isRecentRotation)
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
        }

        // Rotate: revoke the presented token and issue a new one in its place. In the grace case
        // the token is already revoked and keeps pointing at its first successor; the new sibling
        // simply coexists (it expires like any other token if it goes unused).
        var (raw, newHash, expiresAt) = _tokenService.CreateRefreshToken();
        if (stored.IsActive)
        {
            stored.RevokedAtUtc = DateTime.UtcNow;
            stored.ReplacedByTokenHash = newHash;
        }

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
    /// The frontend's public base URL, used to build the password-reset link. Resolution order:
    /// explicit <c>App:PublicBaseUrl</c> config → the request's <c>Origin</c> header (dev: Vite on
    /// :5173 posts cross-origin to :5025) → the request's own scheme+host (prod: nginx serves SPA
    /// and API from one origin, so the API's host <em>is</em> the frontend host).
    /// </summary>
    private string PublicBaseUrl()
    {
        var configured = _config["App:PublicBaseUrl"];
        if (!string.IsNullOrWhiteSpace(configured))
            return configured.TrimEnd('/');

        var origin = Request.Headers.Origin.ToString();
        if (!string.IsNullOrWhiteSpace(origin) && origin != "null")
            return origin.TrimEnd('/');

        return $"{Request.Scheme}://{Request.Host}";
    }

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
