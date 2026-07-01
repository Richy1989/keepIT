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
    /// Notifications are created <em>server-side only</em> (e.g. by the share flow) — there is
    /// deliberately no client-facing create endpoint.
    /// </summary>
    [ApiController]
    [Authorize]
    [Route("api/notifications")]
    public class UserNotificationController : ControllerBase
    {
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

        /// <summary>
        /// Marks all of the caller's notifications as read (clears <c>IsActive</c>). The bell badge
        /// counts active notifications, so opening the dropdown calls this to zero the badge; the
        /// notifications themselves stay listed (and a share invite stays answerable) until dismissed.
        /// </summary>
        /// <returns>204 always (idempotent).</returns>
        [HttpPost("mark-read")]
        public async Task<IActionResult> MarkAllRead()
        {
            var ownerId = User.GetUserId();
            if (ownerId is null) return Unauthorized();

            var changed = await _db.Notifications
                .Where(n => n.OwnerId == ownerId && n.IsActive)
                .ExecuteUpdateAsync(s => s.SetProperty(n => n.IsActive, false));

            if (changed > 0)
                await _notifier.NotifyAsync(ownerId.Value, RealtimeResources.Notification);
            return NoContent();
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
            var callerId = User.GetUserId();
            if (callerId is null) return Unauthorized();

            var notification = await _db.Notifications.FirstOrDefaultAsync(n => n.Id == id && n.OwnerId == callerId);
            if (notification is null) return NotFound();
            if (notification is not ShareInviteNotification invite)
                return BadRequest(new { error = "This notification is not a share invite." });

            if (response.Accept)
            {
                // The note may have been deleted (or its share otherwise resolved) between invite and
                // answer. If it's gone, just consume the invite — there's nothing to grant.
                var note = await _db.Notes.AsNoTracking()
                    .FirstOrDefaultAsync(n => n.Id == invite.SharedNoteId);

                // Guard against a duplicate accept (e.g. two devices) creating a second share row.
                var alreadyShared = note is not null && await _db.NoteShares
                    .AnyAsync(s => s.NoteId == invite.SharedNoteId && s.GranteeId == callerId);

                if (note is not null && !alreadyShared)
                {
                    _db.NoteShares.Add(new NoteShare
                    {
                        Id = Guid.NewGuid(),
                        NoteId = invite.SharedNoteId,
                        GranteeId = callerId.Value,
                        Role = invite.Role,
                        CreatedByUserId = invite.SharedByUserId,
                        CreatedAtUtc = DateTime.UtcNow,
                    });
                    // Give the grantee a per-user view row so the note shows up in their grid.
                    _db.NoteUserStates.Add(new NoteUserState { NoteId = invite.SharedNoteId, UserId = callerId.Value });
                }
            }

            // Either answer consumes the invite.
            _db.Notifications.Remove(invite);
            try
            {
                await _db.SaveChangesAsync();
            }
            catch (DbUpdateException)
            {
                // Two devices answered the same invite concurrently: the unique (NoteId, GranteeId)
                // index rejected the duplicate share, or the invite row was already deleted. The
                // first answer won and the outcome is what the caller wanted — treat as success,
                // but make sure this device's stray invite row (if any) is still consumed.
                _db.ChangeTracker.Clear();
                await _db.Notifications
                    .Where(n => n.Id == id && n.OwnerId == callerId)
                    .ExecuteDeleteAsync();
            }
            // Refresh the caller's notifications (invite gone) and, on accept, their grid (new note).
            await _notifier.NotifyAsync(callerId.Value, RealtimeResources.Notification, RealtimeResources.Notes, RealtimeResources.Lists);
            // Let the owner's collaborators view update too (a share was added/settled).
            await _notifier.NotifyAsync(invite.SharedByUserId, RealtimeResources.Notes);

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
                dto.Role = invite.Role;
            }

            return dto;
        }
    }
}
