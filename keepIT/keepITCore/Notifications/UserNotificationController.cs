using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Notifications.Dtos;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notifications
{
    /// <summary>
    /// The caller's notifications — a per-user list of messages, each with a <see cref="NotificationType"/>
    /// that tells the client what to render and which actions to offer (a plain System message is
    /// dismiss-only; a ShareInvite can be accepted or declined). Every action is scoped to the
    /// authenticated user; a caller can never see, answer, or delete another user's notifications.
    /// </summary>
    [ApiController]
    [Authorize]
    [Route("api/notifications")]
    public class UserNotificationController : ControllerBase
    {
        /// <summary>Allowed severity levels. Keep in sync with the frontend's severity set.</summary>
        private static readonly HashSet<string> AllowedSeverity = new(StringComparer.Ordinal) { "warning", "error", "information" };

        private readonly AppDbContext _db;
        private readonly IRealtimeNotifier _notifier;

        /// <summary>Injects the database context and the realtime change notifier.</summary>
        /// <param name="db">The EF Core context.</param>
        /// <param name="notifier">Pushes change signals to the caller's other devices.</param>
        public UserNotificationController(AppDbContext db, IRealtimeNotifier notifier)
        {
            _db = db;
            _notifier = notifier;
        }

        /// <summary>Lists the caller's notifications, newest first.</summary>
        /// <returns>200 with the caller's notifications.</returns>
        [HttpGet]
        public async Task<ActionResult<List<UserNotificationDto>>> GetNotifications()
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            var notifications = await _db.Notifications.AsNoTracking()
                .Where(x => x.OwnerId == ownerId)
                .OrderByDescending(x => x.CreatedAtUtc)
                .ToListAsync();

            return Ok(notifications.Select(ToDto).ToList());
        }

        /// <summary>Creates a plain <see cref="NotificationType.System"/> notification for the caller.</summary>
        /// <param name="notificationDto">The notification's text and severity. Everything else is ignored.</param>
        /// <returns>201 with the created notification, or 400 on an invalid severity.</returns>
        [HttpPost]
        public async Task<ActionResult<UserNotificationDto>> AddNotification(UserNotificationDto notificationDto)
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            if (!AllowedSeverity.Contains(notificationDto.Severity))
                ModelState.AddModelError(nameof(notificationDto.Severity), $"Severity must be one of: {string.Join(", ", AllowedSeverity)}.");
            if (!ModelState.IsValid)
                return ValidationProblem(ModelState);

            var newNotification = new SystemNotification
            {
                Id = Guid.NewGuid(),
                OwnerId = ownerId.Value,
                NotificationText = notificationDto.NotificationText,
                Severity = notificationDto.Severity,
                IsActive = true,
                CreatedAtUtc = DateTime.UtcNow,
            };

            _db.Notifications.Add(newNotification);
            await _db.SaveChangesAsync();
            await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Notification);

            return CreatedAtAction(nameof(GetNotifications), new { id = newNotification.Id }, ToDto(newNotification));
        }

        /// <summary>
        /// Answers a share-invite notification: accept the share (grant access) or decline it. Either
        /// way the invite is consumed and removed. Only the recipient can answer their own invite.
        /// </summary>
        /// <param name="id">The notification id (must be a ShareInvite owned by the caller).</param>
        /// <param name="response">Whether the caller accepts or declines.</param>
        /// <returns>204 on success, 404 if it isn't the caller's, or 400 if it isn't a share invite.</returns>
        [HttpPost("{id:guid}/respond")]
        public async Task<IActionResult> RespondToShare(Guid id, ShareResponseDto response)
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            var notification = await _db.Notifications.FirstOrDefaultAsync(n => n.Id == id && n.OwnerId == ownerId);
            if (notification is null) return NotFound();
            if (notification is not ShareInviteNotification invite)
                return BadRequest(new { error = "This notification is not a share invite." });

            // TODO (sharing feature): on accept, grant the recipient access to invite.SharedNoteId by
            // creating the NoteShare row; on decline, drop the pending share. Until the sharing feature
            // and its NoteShare entity exist, answering simply consumes the invite. `invite` and
            // `response.Accept` carry everything that wiring will need.
            _db.Notifications.Remove(invite);
            await _db.SaveChangesAsync();
            await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Notification);

            return NoContent();
        }

        /// <summary>Permanently deletes (dismisses) one of the caller's notifications.</summary>
        /// <param name="id">The notification id.</param>
        /// <returns>204 on success, or 404 if it doesn't exist or isn't the caller's.</returns>
        [HttpDelete("{id:guid}")]
        public async Task<IActionResult> Delete(Guid id)
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            var notification = await _db.Notifications.FirstOrDefaultAsync(n => n.Id == id && n.OwnerId == ownerId);
            if (notification is null) return NotFound();

            _db.Notifications.Remove(notification);
            await _db.SaveChangesAsync();
            await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Notification);
            return NoContent();
        }

        /// <summary>Projects a notification entity to its client DTO, flattening subtype fields.</summary>
        /// <param name="s">The notification entity (any concrete subtype).</param>
        /// <returns>The DTO returned to the client; ShareInvite fields are populated only for invites.</returns>
        private static UserNotificationDto ToDto(UserNotification s)
        {
            var dto = new UserNotificationDto
            {
                Id = s.Id,
                Type = s.Type,
                NotificationText = s.NotificationText,
                Severity = s.Severity,
                IsActive = s.IsActive,
                CreatedAtUtc = s.CreatedAtUtc,
            };

            if (s is ShareInviteNotification invite)
            {
                dto.SharedNoteId = invite.SharedNoteId;
                dto.SharedNoteTitle = invite.SharedNoteTitle;
                dto.SharedByUserEmail = invite.SharedByUserEmail;
            }

            return dto;
        }
    }
}
