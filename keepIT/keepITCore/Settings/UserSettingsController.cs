using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Settings.Dtos;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Settings
{
    /// <summary>
    /// Per-user UI settings (theme, accent color). One settings row per user, created lazily.
    /// Every action is scoped to the authenticated caller.
    /// </summary>
    [ApiController]
    [Authorize]
    [Route("api/settings")]
    public class UserSettingsController : ControllerBase
    {
        /// <summary>Allowed theme preferences. Keep in sync with the frontend theme set.</summary>
        private static readonly HashSet<string> AllowedThemes =
            new(StringComparer.Ordinal) { "light", "dim", "dark", "system" };

        /// <summary>Allowed accent color keys. Keep in sync with the frontend accent palette.</summary>
        private static readonly HashSet<string> AllowedAccents =
            new(StringComparer.Ordinal) { "yellow", "orange", "red", "pink", "purple", "blue", "teal", "green" };

        private readonly AppDbContext _db;

        /// <summary>Injects the database context.</summary>
        /// <param name="db">The EF Core context.</param>
        public UserSettingsController(AppDbContext db) => _db = db;

        /// <summary>Returns the caller's settings, creating defaults on first access.</summary>
        /// <returns>200 with the settings.</returns>
        [HttpGet]
        public async Task<ActionResult<UserSettingsDto>> GetUserSettings()
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            var settings = await _db.UserSettings.AsNoTracking()
                .FirstOrDefaultAsync(s => s.OwnerId == ownerId);

            settings ??= await CreateDefaultsAsync(ownerId.Value);
            return Ok(ToDto(settings));
        }

        /// <summary>Updates the caller's theme and accent color (upserts the row).</summary>
        /// <param name="dto">The new settings. Id is ignored.</param>
        /// <returns>200 with the saved settings, or 400 on an invalid theme/accent value.</returns>
        [HttpPut]
        public async Task<ActionResult<UserSettingsDto>> UpdateUserSettings(UserSettingsDto dto)
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            if (!AllowedThemes.Contains(dto.Theme))
                ModelState.AddModelError(nameof(dto.Theme), $"Theme must be one of: {string.Join(", ", AllowedThemes)}.");
            if (!AllowedAccents.Contains(dto.GlobalAccentColor))
                ModelState.AddModelError(nameof(dto.GlobalAccentColor), $"Accent must be one of: {string.Join(", ", AllowedAccents)}.");
            if (!ModelState.IsValid)
                return ValidationProblem(ModelState);

            var settings = await _db.UserSettings.FirstOrDefaultAsync(s => s.OwnerId == ownerId);
            if (settings is null)
            {
                settings = new UserSettings { OwnerId = ownerId.Value };
                _db.UserSettings.Add(settings);
            }

            settings.Theme = dto.Theme;
            settings.GlobalAccentColor = dto.GlobalAccentColor;
            await _db.SaveChangesAsync();

            return Ok(ToDto(settings));
        }

        /// <summary>Inserts and returns a default settings row for the user.</summary>
        /// <param name="ownerId">The owning user's id.</param>
        /// <returns>The newly created settings entity.</returns>
        private async Task<UserSettings> CreateDefaultsAsync(Guid ownerId)
        {
            var settings = new UserSettings { OwnerId = ownerId };
            _db.UserSettings.Add(settings);
            await _db.SaveChangesAsync();
            return settings;
        }

        /// <summary>Projects a settings entity to its client DTO.</summary>
        /// <param name="s">The settings entity.</param>
        /// <returns>The DTO returned to the client.</returns>
        private static UserSettingsDto ToDto(UserSettings s) => new()
        {
            Id = s.Id,
            GlobalAccentColor = s.GlobalAccentColor,
            Theme = s.Theme,
        };
    }
}
