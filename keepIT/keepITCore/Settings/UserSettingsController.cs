using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Infrastructure.Email;
using keepITCore.Service;
using keepITCore.Settings.Dtos;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

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

        private static readonly string[] AllowedExtensions = { ".jpg", ".jpeg", ".png", ".webp", ".gif" };
        private const long MaxFileSize = 2 * 1024 * 1024; // 2 MB

        private readonly AppDbContext _db;
        private readonly ImageService _imgService;
        private readonly IRealtimeNotifier _notifier;
        private readonly IEmailSender _emailSender;
        private readonly EmailOptions _emailOptions;
        private readonly ILogger<UserSettingsController> _logger;

        /// <summary>Injects the database context, the image storage service, the realtime notifier, and email.</summary>
        /// <param name="db">The EF Core context.</param>
        /// <param name="imgService">Validates and stores uploaded images.</param>
        /// <param name="notifier">Pushes change signals to the caller's other devices.</param>
        /// <param name="emailSender">Delivers the test email (SMTP, or the server log when unconfigured).</param>
        /// <param name="emailOptions">Email settings, read to report whether SMTP is configured.</param>
        /// <param name="logger">Controller logger.</param>
        public UserSettingsController(
            AppDbContext db,
            ImageService imgService,
            IRealtimeNotifier notifier,
            IEmailSender emailSender,
            IOptions<EmailOptions> emailOptions,
            ILogger<UserSettingsController> logger)
        {
            _db = db;
            _imgService = imgService;
            _notifier = notifier;
            _emailSender = emailSender;
            _emailOptions = emailOptions.Value;
            _logger = logger;
        }
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
            // Theme/accent are per-user but multi-device: the user's other open devices restyle live.
            await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Settings);

            return Ok(ToDto(settings));
        }

        /// <summary>
        /// Sends a test email to the caller's own account address, to check the server's email
        /// configuration. Always 200 — the result payload distinguishes delivered-via-SMTP,
        /// written-to-the-server-log (SMTP unconfigured), and failed (with the delivery error, so
        /// the operator can fix the <c>Email__*</c> settings). Only the caller's own address can be
        /// targeted, so the endpoint can't be used to spam third parties.
        /// </summary>
        /// <param name="ct">Cancellation token.</param>
        /// <returns>200 with the test result.</returns>
        [HttpPost("test-email")]
        public async Task<ActionResult<TestEmailResultDto>> SendTestEmail(CancellationToken ct)
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            var email = await _db.Users.AsNoTracking()
                .Where(u => u.Id == ownerId)
                .Select(u => u.Email)
                .FirstOrDefaultAsync(ct);
            if (email is null) return Unauthorized();

            var result = new TestEmailResultDto
            {
                SmtpConfigured = _emailOptions.IsConfigured,
                SentTo = email,
            };

            try
            {
                await _emailSender.SendAsync(
                    email,
                    "keepIT test email",
                    "This is a test email from your keepIT server.\n\n" +
                    "If you're reading this in your inbox, email delivery (SMTP) is configured " +
                    "correctly and password-reset links will reach their recipients.",
                    ct);
                result.Sent = true;
            }
            catch (Exception ex)
            {
                // Surfacing the error is the point of the test: the caller is checking their own
                // server's SMTP settings and needs to see what went wrong.
                _logger.LogError(ex, "Test email delivery failed");
                result.Error = ex.Message;
            }

            return Ok(result);
        }

        /// <summary>
        /// Uploads the caller's profile image. The upload is validated by extension, size, and the
        /// file's actual content signature (see <see cref="ImageService"/>); the previous image file
        /// is deleted so re-uploads don't accumulate orphans on the data volume.
        /// </summary>
        /// <param name="file">The image (jpg/png/webp/gif, ≤2 MB).</param>
        /// <param name="ct">Cancellation token.</param>
        /// <returns>200 with the stored filename, or 400 on an invalid file.</returns>
        [HttpPost("uploadProfileImage")]
        [RequestSizeLimit(3 * 1024 * 1024)] // multipart overhead headroom above the 2 MB payload cap
        public async Task<IActionResult> Upload(IFormFile file, CancellationToken ct)
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            if (file is null || file.Length == 0)
                return BadRequest("No file provided.");

            if (file.Length > MaxFileSize)
                return BadRequest("File too large.");

            var ext = Path.GetExtension(file.FileName).ToLowerInvariant();
            if (!AllowedExtensions.Contains(ext))
                return BadRequest("Unsupported file type.");

            var applicationUser = await _db.Users.FirstOrDefaultAsync(u => u.Id == ownerId, ct);
            if (applicationUser is null) return NotFound();

            string uploadPath = FolderManagement.GetUserProfileImageFolder(ownerId.Value.ToString());
            var fileName = await _imgService.Upload(file, uploadPath, ct);
            if (fileName is null)
                return BadRequest("The file is not a valid image.");

            var previous = applicationUser.ProfileImageFileName;
            applicationUser.ProfileImageFileName = fileName;
            await _db.SaveChangesAsync(ct);

            // The DB now points at the new file — the old one is unreachable, so remove it.
            if (!string.IsNullOrEmpty(previous))
            {
                var previousPath = Path.Combine(uploadPath, previous);
                try { System.IO.File.Delete(previousPath); }
                catch (IOException) { /* best-effort cleanup; an orphan is harmless */ }
            }

            await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Settings);
            return Ok(new { fileName });
        }

        /// <summary>
        /// Streams a user's profile image. A caller may fetch their own avatar, or the avatar of a
        /// user they're connected to through sharing (note owner ↔ collaborator, fellow collaborators
        /// on the same note, or a pending invite between them) — that's what the share UI needs,
        /// without making every avatar public to any signed-in user.
        /// </summary>
        /// <param name="userId">The user whose image to fetch.</param>
        /// <param name="ct">Cancellation token.</param>
        /// <returns>200 with the image, or 404 if there is none or the caller isn't connected to them.</returns>
        [HttpGet("getProfileImage/{userId:guid}")]
        public async Task<IActionResult> GetProfileImage(Guid userId, CancellationToken ct)
        {
            var callerId = User.GetUserId();
            if (callerId is null) return Unauthorized();

            // Same 404 for "no image" and "no permission" so the endpoint can't be used to probe ids.
            if (userId != callerId && !await AreConnectedAsync(callerId.Value, userId, ct))
                return NotFound();

            var fileName = await _db.Users
                .Where(u => u.Id == userId)
                .Select(u => u.ProfileImageFileName)
                .FirstOrDefaultAsync(ct);

            if (fileName is null)
                return NotFound();

            var path = Path.Combine(FolderManagement.GetUserProfileImageFolder(userId.ToString()), fileName);
            if (!System.IO.File.Exists(path))
                return NotFound();

            var contentType = Path.GetExtension(fileName).ToLowerInvariant() switch
            {
                ".png" => "image/png",
                ".webp" => "image/webp",
                ".gif" => "image/gif",
                _ => "image/jpeg",
            };

            var stream = System.IO.File.OpenRead(path);
            return File(stream, contentType); // streams the file to the client
        }

        /// <summary>
        /// True when two users are related through sharing: one owns a note the other collaborates
        /// on, they collaborate on the same note, or a share invite is pending between them.
        /// </summary>
        /// <param name="callerId">The requesting user.</param>
        /// <param name="targetId">The user whose avatar is requested.</param>
        /// <param name="ct">Cancellation token.</param>
        private async Task<bool> AreConnectedAsync(Guid callerId, Guid targetId, CancellationToken ct)
        {
            var viaShares = await _db.NoteShares.AnyAsync(s =>
                (s.GranteeId == targetId && (s.Note.OwnerId == callerId
                    || _db.NoteShares.Any(o => o.NoteId == s.NoteId && o.GranteeId == callerId)))
                || (s.GranteeId == callerId && s.Note.OwnerId == targetId), ct);
            if (viaShares) return true;

            return await _db.Notifications.OfType<ShareInviteNotification>().AnyAsync(n =>
                (n.OwnerId == callerId && n.SharedByUserId == targetId) ||
                (n.OwnerId == targetId && n.SharedByUserId == callerId), ct);
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
