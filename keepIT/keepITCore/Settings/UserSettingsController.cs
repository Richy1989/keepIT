using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Notes.Dtos;
using keepITCore.Settings.Dtos;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Settings
{
    [ApiController]
    [Authorize]
    [Route("api/settings")]
    public class UserSettingsController : ControllerBase
    {
        private readonly AppDbContext _db;

        /// <summary>Injects the database context.</summary>
        /// <param name="db">The EF Core context.</param>
        public UserSettingsController(AppDbContext db) => _db = db;

        [HttpGet]
        public async Task<ActionResult<UserSettingsDto>> GetUserSettings()
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            var query = _db.UserSettings.AsNoTracking().Where(n => n.OwnerId == ownerId);
            var setting = await query.FirstOrDefaultAsync();

            if (setting is null) return Ok(await CreateNewSettings(ownerId.Value));

            return Ok(ToDto(setting));
        }

        private async Task<UserSettingsDto> CreateNewSettings(Guid ownerId)
        {
            var settings = new UserSettings
            {
               // Id = Guid.NewGuid(),
                OwnerId = ownerId
            };
            _db.UserSettings.Add(settings);
            await _db.SaveChangesAsync();

            //var created = await LoadDtoAsync(settings.Id, settings.OwnerId);
            return ToDto(settings);
        }

        private static UserSettingsDto ToDto(UserSettings n) => new()
        {
            Id = n.Id,
        };

        //private async Task<UserSettingsDto?> LoadDtoAsync(Guid id, Guid ownerId)
        //{
        //    var settings = await _db.UserSettings.AsNoTracking()
        //        .Where(n => n.Id == id && n.OwnerId == ownerId)
        //        .FirstOrDefaultAsync();

        //    return settings is null ? null : ToDto(settings);
        //}
    }
}
