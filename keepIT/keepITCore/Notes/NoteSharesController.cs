using keepITCore.Auth;
using keepITCore.Data;
using keepITCore.Notes.Dtos;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// Manages who a note is shared with (ARCHITECTURE.md "Sharing / collaboration"). Sharing is an
/// <em>invite</em> flow: the owner invites a user, who gets a <see cref="ShareInviteNotification"/>
/// and gains access only once they accept it (creating the <see cref="NoteShare"/> — handled by the
/// notifications endpoint). The owner is the only one who can invite, change roles, or revoke; a
/// collaborator may revoke their own share to leave a note.
/// </summary>
[ApiController]
[Authorize]
[Route("api/notes/{noteId:guid}/shares")]
public class NoteSharesController : ControllerBase
{
    private readonly AppDbContext _db;
    private readonly IRealtimeNotifier _notifier;
    private readonly NoteAccessService _access;

    /// <summary>Injects the database context, the realtime notifier, and the access resolver.</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="notifier">Pushes change signals to affected users' devices.</param>
    /// <param name="access">Resolves the caller's access to the note.</param>
    public NoteSharesController(AppDbContext db, IRealtimeNotifier notifier, NoteAccessService access)
    {
        _db = db;
        _notifier = notifier;
        _access = access;
    }

    /// <summary>Lists the note's collaborators and their roles. Any user with access may read it.</summary>
    /// <param name="noteId">The note whose collaborators to list.</param>
    /// <returns>200 with the collaborators, or 404 if the caller has no access to the note.</returns>
    [HttpGet]
    public async Task<ActionResult<List<NoteShareDto>>> GetShares(Guid noteId)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();

        var shares = await _db.NoteShares.AsNoTracking()
            .Where(s => s.NoteId == noteId)
            .OrderBy(s => s.CreatedAtUtc)
            .Select(s => new NoteShareDto
            {
                GranteeId = s.GranteeId,
                Email = s.Grantee.Email ?? "",
                Role = s.Role,
                CreatedAtUtc = s.CreatedAtUtc,
            })
            .ToListAsync();

        return Ok(shares);
    }

    /// <summary>
    /// Invites a user (by email) to collaborate on the note at a role. Creates a pending
    /// <see cref="ShareInviteNotification"/> for the recipient — access is granted only when they
    /// accept. Owner-only.
    /// </summary>
    /// <param name="noteId">The note to share (must be the caller's).</param>
    /// <param name="dto">The recipient's email and the offered role.</param>
    /// <returns>204 on success; 400 for an invalid recipient/self/duplicate; 403 for non-owners; 404 if no access.</returns>
    [HttpPost]
    public async Task<IActionResult> CreateShare(Guid noteId, CreateShareDto dto)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.IsOwner) return Forbid();

        var recipient = await _db.Users
            .FirstOrDefaultAsync(u => u.NormalizedEmail == dto.Email.ToUpperInvariant());
        if (recipient is null)
            return BadRequest(new { error = "No user with that email." });
        if (recipient.Id == callerId)
            return BadRequest(new { error = "You can't share a note with yourself." });

        if (await _db.NoteShares.AnyAsync(s => s.NoteId == noteId && s.GranteeId == recipient.Id))
            return BadRequest(new { error = "This note is already shared with that user." });
        if (await _db.Notifications.OfType<ShareInviteNotification>()
                .AnyAsync(n => n.OwnerId == recipient.Id && n.SharedNoteId == noteId))
            return BadRequest(new { error = "An invite for that user is already pending." });

        var note = await _db.Notes.AsNoTracking().FirstOrDefaultAsync(n => n.Id == noteId);
        if (note is null) return NotFound();

        var sharerEmail = await _db.Users.Where(u => u.Id == callerId).Select(u => u.Email).FirstOrDefaultAsync();

        var invite = new ShareInviteNotification
        {
            Id = Guid.NewGuid(),
            OwnerId = recipient.Id,
            SharedNoteId = noteId,
            SharedNoteTitle = note.Title,
            SharedByUserId = callerId.Value,
            SharedByUserEmail = sharerEmail,
            Role = dto.Role,
            NotificationText = $"{sharerEmail} wants to share \"{TitleOrUntitled(note.Title)}\" with you.",
            Severity = "information",
            IsActive = true,
            CreatedAtUtc = DateTime.UtcNow,
        };

        _db.Notifications.Add(invite);
        await _db.SaveChangesAsync();
        await _notifier.NotifyAsync(recipient.Id, RealtimeResources.Notification);
        return NoContent();
    }

    /// <summary>Changes a collaborator's role on the note. Owner-only.</summary>
    /// <param name="noteId">The note.</param>
    /// <param name="granteeId">The collaborator whose role to change.</param>
    /// <param name="dto">The new role.</param>
    /// <returns>204 on success, 403 for non-owners, or 404 if the note or share isn't found.</returns>
    [HttpPatch("{granteeId:guid}")]
    public async Task<IActionResult> UpdateRole(Guid noteId, Guid granteeId, UpdateShareRoleDto dto)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();
        if (!access.Value.IsOwner) return Forbid();

        var share = await _db.NoteShares.FirstOrDefaultAsync(s => s.NoteId == noteId && s.GranteeId == granteeId);
        if (share is null) return NotFound();

        share.Role = dto.Role;
        await _db.SaveChangesAsync();
        // The grantee's edit permission changed — refresh their notes so the UI locks/unlocks editing.
        await _notifier.NotifyAsync(granteeId, RealtimeResources.Notes);
        return NoContent();
    }

    /// <summary>
    /// Revokes a share. The owner may revoke anyone; a collaborator may revoke their <em>own</em>
    /// share to leave the note. Removing the share also drops the grantee's per-user state, so the
    /// note disappears from their grid immediately.
    /// </summary>
    /// <param name="noteId">The note.</param>
    /// <param name="granteeId">The collaborator whose share to revoke.</param>
    /// <returns>204 on success, 403 if the caller may not revoke this share, or 404 if not found.</returns>
    [HttpDelete("{granteeId:guid}")]
    public async Task<IActionResult> RevokeShare(Guid noteId, Guid granteeId)
    {
        var callerId = User.GetUserId();
        if (callerId is null) return Unauthorized();

        var access = await _access.ResolveAsync(noteId, callerId.Value);
        if (access is null) return NotFound();
        // Owner can revoke anyone; a collaborator can only remove themselves (leave).
        if (!access.Value.IsOwner && callerId != granteeId) return Forbid();

        var share = await _db.NoteShares.FirstOrDefaultAsync(s => s.NoteId == noteId && s.GranteeId == granteeId);
        if (share is null) return NotFound();

        _db.NoteShares.Remove(share);
        var state = await _db.NoteUserStates.FirstOrDefaultAsync(us => us.NoteId == noteId && us.UserId == granteeId);
        if (state is not null) _db.NoteUserStates.Remove(state);
        // The grantee's private list memberships on this note are theirs alone — drop them too.
        var memberships = await _db.NoteLists.Where(nl => nl.NoteId == noteId && nl.UserId == granteeId).ToListAsync();
        _db.NoteLists.RemoveRange(memberships);

        await _db.SaveChangesAsync();
        // Access is gone: the grantee's devices must drop the note from their grid.
        await _notifier.NotifyAsync(granteeId, RealtimeResources.Notes, RealtimeResources.Lists);
        return NoContent();
    }

    /// <summary>A display-safe note title for invite text.</summary>
    private static string TitleOrUntitled(string? title) =>
        string.IsNullOrWhiteSpace(title) ? "Untitled note" : title;
}
