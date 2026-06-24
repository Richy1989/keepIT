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
        public async Task<ActionResult<UserSettings>> GetNotes()
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            var query = _db.UserSettings.AsNoTracking().Where(n => n.OwnerId == ownerId);
            var setting = await query.FirstOrDefaultAsync();

            setting ??= new UserSettings();

            return Ok(ToDto(setting));
        }

        private static UserSettingsDto ToDto(UserSettings n) => new()
        {
            Id = n.Id,
        };
    }
}
