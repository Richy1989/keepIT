using keepITCore.Auth.Dtos;
using keepITCore.Data;
using keepITCore.Infrastructure.Security;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;

namespace keepITCore.Auth;

/// <summary>
/// The signed-in user's two-factor authentication: set up an authenticator app, turn it on with a
/// code from it, turn it off, and replace the recovery codes. Signing in with a code is
/// <see cref="AuthController.Login"/>'s; see <see cref="TwoFactorService"/> for how it works.
/// <para>Each step asks for proof that a device left signed in can't give: setting up asks for the
/// password (or someone could tie the account to their own phone and lock the owner out), and once
/// it is on, turning it off or seeing new recovery codes asks for the password and a code. Changes
/// push <see cref="RealtimeResources.Account"/>, so the user's other devices show the new state.</para>
/// </summary>
[ApiController]
[Route("api/auth/two-factor")]
[Authorize]
public class TwoFactorController : ControllerBase
{
    private readonly UserManager<ApplicationUser> _userManager;
    private readonly TwoFactorService _twoFactor;
    private readonly IRealtimeNotifier _notifier;

    /// <summary>Injects Identity's user manager, the two-factor service and realtime.</summary>
    /// <param name="userManager">Finds the caller and checks their password.</param>
    /// <param name="twoFactor">Keys, codes and recovery codes.</param>
    /// <param name="notifier">Tells the user's other devices that their account changed.</param>
    public TwoFactorController(UserManager<ApplicationUser> userManager, TwoFactorService twoFactor, IRealtimeNotifier notifier)
    {
        _userManager = userManager;
        _twoFactor = twoFactor;
        _notifier = notifier;
    }

    /// <summary>Whether two-factor authentication is on, and how many recovery codes are left.</summary>
    /// <returns>200 with the status, or 401 if unauthenticated.</returns>
    [HttpGet]
    public async Task<ActionResult<TwoFactorStatusDto>> Status()
    {
        var user = await CurrentUserAsync();
        if (user is null) return Unauthorized();

        return Ok(new TwoFactorStatusDto
        {
            Enabled = user.TwoFactorEnabled,
            RecoveryCodesLeft = user.TwoFactorEnabled ? await _twoFactor.RecoveryCodesLeftAsync(user) : 0,
        });
    }

    /// <summary>
    /// Starts setting up an authenticator app: a new key, as a QR code and as text. Sign-in is
    /// unchanged until <see cref="Enable"/> confirms a code from it; starting again replaces the key.
    /// </summary>
    /// <param name="dto">The account's password.</param>
    /// <returns>200 with the key, 400 if the password is wrong, 409 if two-factor authentication is
    /// already on (a new key would end the authenticator in use), or 401 if unauthenticated.</returns>
    [HttpPost("setup")]
    [EnableRateLimiting(RateLimitPolicies.Auth)]
    public async Task<ActionResult<TwoFactorSetupDto>> Setup(TwoFactorSetupRequestDto dto)
    {
        var user = await CurrentUserAsync();
        if (user is null) return Unauthorized();

        if (user.TwoFactorEnabled)
            return Conflict(new { error = "Two-factor authentication is already on. Turn it off first to use another app." });

        if (!await _userManager.CheckPasswordAsync(user, dto.Password))
            return WrongPassword(nameof(dto.Password));

        return Ok(await _twoFactor.BeginSetupAsync(user));
    }

    /// <summary>
    /// Turns two-factor authentication on, with a code from the app just set up, and returns the
    /// first recovery codes. From here on, signing in asks for a code.
    /// </summary>
    /// <param name="dto">The code the app shows.</param>
    /// <returns>200 with the recovery codes, 400 if the code is wrong or no setup was started, 409
    /// if it is already on, or 401 if unauthenticated.</returns>
    [HttpPost("enable")]
    [EnableRateLimiting(RateLimitPolicies.Auth)]
    public async Task<ActionResult<TwoFactorRecoveryCodesDto>> Enable(TwoFactorEnableRequestDto dto)
    {
        var user = await CurrentUserAsync();
        if (user is null) return Unauthorized();

        if (user.TwoFactorEnabled)
            return Conflict(new { error = "Two-factor authentication is already on." });

        if (await _userManager.GetAuthenticatorKeyAsync(user) is null)
            return BadRequest(new { error = "Start the setup first." });

        var codes = await _twoFactor.EnableAsync(user, dto.Code);
        if (codes is null)
        {
            ModelState.AddModelError(nameof(dto.Code),
                "That code didn't match. Check the phone's time is set automatically, and enter the code the app shows now.");
            return ValidationProblem(ModelState);
        }

        await _notifier.NotifyAsync(user.Id, RealtimeResources.Account);
        return Ok(new TwoFactorRecoveryCodesDto { Codes = codes });
    }

    /// <summary>
    /// Turns two-factor authentication off: signing in needs only the password again. The
    /// authenticator entry and recovery codes stop working, so turning it back on starts afresh.
    /// </summary>
    /// <param name="dto">The password, and a code from the app or a recovery code.</param>
    /// <returns>204, 400 if the password or code is wrong, 409 if it is already off, or 401 if
    /// unauthenticated.</returns>
    [HttpPost("disable")]
    [EnableRateLimiting(RateLimitPolicies.Auth)]
    public async Task<IActionResult> Disable(TwoFactorConfirmRequestDto dto)
    {
        var user = await CurrentUserAsync();
        if (user is null) return Unauthorized();

        if (!user.TwoFactorEnabled)
            return Conflict(new { error = "Two-factor authentication is already off." });

        if (await ConfirmBothFactorsAsync(user, dto) is { } refused)
            return refused;

        await _twoFactor.DisableAsync(user);
        await _notifier.NotifyAsync(user.Id, RealtimeResources.Account);
        return NoContent();
    }

    /// <summary>
    /// Replaces the recovery codes with a new set: the old ones stop working. For when they have
    /// been used up, or the paper they were written on is gone.
    /// </summary>
    /// <param name="dto">The password, and a code from the app or a recovery code.</param>
    /// <returns>200 with the new codes, 400 if the password or code is wrong, 409 if two-factor
    /// authentication is off, or 401 if unauthenticated.</returns>
    [HttpPost("recovery-codes")]
    [EnableRateLimiting(RateLimitPolicies.Auth)]
    public async Task<ActionResult<TwoFactorRecoveryCodesDto>> NewRecoveryCodes(TwoFactorConfirmRequestDto dto)
    {
        var user = await CurrentUserAsync();
        if (user is null) return Unauthorized();

        if (!user.TwoFactorEnabled)
            return Conflict(new { error = "Two-factor authentication is off, so there are no recovery codes." });

        if (await ConfirmBothFactorsAsync(user, dto) is { } refused)
            return refused;

        var codes = await _twoFactor.NewRecoveryCodesAsync(user);
        await _notifier.NotifyAsync(user.Id, RealtimeResources.Account);
        return Ok(new TwoFactorRecoveryCodesDto { Codes = codes });
    }

    // ---- helpers ----

    /// <summary>The caller's account, or null when the token names none.</summary>
    private async Task<ApplicationUser?> CurrentUserAsync()
    {
        var userId = User.GetUserId();
        return userId is null ? null : await _userManager.FindByIdAsync(userId.Value.ToString());
    }

    /// <summary>
    /// Checks the password, then the code. Null when both are right; otherwise the 400 to answer,
    /// naming the one that is wrong. The password goes first, so a wrong one never uses up a
    /// recovery code.
    /// </summary>
    private async Task<ActionResult?> ConfirmBothFactorsAsync(ApplicationUser user, TwoFactorConfirmRequestDto dto)
    {
        if (!await _userManager.CheckPasswordAsync(user, dto.Password))
            return WrongPassword(nameof(dto.Password));

        if (!await _twoFactor.VerifySecondFactorAsync(user, dto.Code))
        {
            ModelState.AddModelError(nameof(dto.Code), "That code didn't work. Enter the one your app shows now, or a recovery code.");
            return ValidationProblem(ModelState);
        }

        return null;
    }

    private ActionResult WrongPassword(string field)
    {
        ModelState.AddModelError(field, "That password isn't right.");
        return ValidationProblem(ModelState);
    }
}
