using keepITCore.Data;
using keepITCore.Infrastructure;
using keepITCore.Service;
using keepITCore.SignalR;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Auth;

/// <summary>
/// Deletes an account and everything it owns, for <c>POST api/auth/delete-account</c>.
/// <para>
/// Most of it is the database's cascade from the user row: the user's own notes (and with each, its
/// checklist, media rows, shares, list memberships, reminders and every collaborator's view of it),
/// their lists, settings, inbox and sign-in sessions, and their own state on notes shared with them.
/// Three things are not, and are handled here:
/// </para>
/// <list type="bullet">
///   <item>Shares <i>to</i> the user: that foreign key is Restrict on purpose, so no user delete
///   ever severs a share by accident. Deleting the account is the one deliberate way, so they go
///   first — the user leaves every note shared with them, and its owner keeps it.</item>
///   <item>Other people's inbox entries that point at the account by id rather than by key: share
///   invites the user sent (still pending), and reminders that fired on the user's notes for a
///   collaborator. Left in place they would lead to notes that no longer exist.</item>
///   <item>Files: every photo and recording of the user's notes, and the profile picture, under the
///   user's folder in the data root.</item>
/// </list>
/// <para>
/// The database work is one transaction, so a failure leaves the account whole rather than half
/// deleted. Files go after the commit (a file left behind is harmless and the media sweep
/// collects it; a note whose files went while its rows stayed is not). Everyone whose view changed
/// is told over realtime: collaborators who lose the user's notes, owners who lose the user as a
/// collaborator, and people with an invite or reminder from the account.
/// </para>
/// </summary>
public class AccountDeletionService
{
    private readonly AppDbContext _db;
    private readonly UserManager<ApplicationUser> _userManager;
    private readonly IMediaStorage _media;
    private readonly IRealtimeNotifier _notifier;
    private readonly ILogger<AccountDeletionService> _logger;

    public AccountDeletionService(
        AppDbContext db,
        UserManager<ApplicationUser> userManager,
        IMediaStorage media,
        IRealtimeNotifier notifier,
        ILogger<AccountDeletionService> logger)
    {
        _db = db;
        _userManager = userManager;
        _media = media;
        _notifier = notifier;
        _logger = logger;
    }

    /// <summary>Deletes <paramref name="user"/> and everything they own.</summary>
    /// <returns>Whether the account was deleted; false only when Identity refused.</returns>
    public async Task<bool> DeleteAsync(ApplicationUser user, CancellationToken ct = default)
    {
        var userId = user.Id;
        var ownedNoteIds = await _db.Notes.Where(n => n.OwnerId == userId).Select(n => n.Id).ToListAsync(ct);

        // Who has to hear about it, gathered before the rows go.
        var collaborators = await _db.NoteShares
            .Where(s => ownedNoteIds.Contains(s.NoteId))
            .Select(s => s.GranteeId)
            .ToListAsync(ct);
        var ownersOfShared = await _db.NoteShares
            .Where(s => s.GranteeId == userId)
            .Select(s => s.Note.OwnerId)
            .ToListAsync(ct);
        var strayInvites = _db.Notifications.OfType<ShareInviteNotification>()
            .Where(n => n.OwnerId != userId && (n.SharedByUserId == userId || ownedNoteIds.Contains(n.SharedNoteId)));
        var strayReminders = _db.Notifications.OfType<ReminderNotification>()
            .Where(n => n.OwnerId != userId && ownedNoteIds.Contains(n.ReminderNoteId));
        var inboxOwners = await strayInvites.Select(n => n.OwnerId)
            .Concat(strayReminders.Select(n => n.OwnerId))
            .ToListAsync(ct);

        await using (var tx = await _db.Database.BeginTransactionAsync(ct))
        {
            await _db.NoteShares.Where(s => s.GranteeId == userId).ExecuteDeleteAsync(ct);
            await strayInvites.ExecuteDeleteAsync(ct);
            await strayReminders.ExecuteDeleteAsync(ct);

            var result = await _userManager.DeleteAsync(user);
            if (!result.Succeeded)
            {
                _logger.LogError(
                    "Deleting account {UserId} failed: {Errors}",
                    userId, string.Join("; ", result.Errors.Select(e => e.Description)));
                return false;
            }
            await tx.CommitAsync(ct);
        }

        foreach (var noteId in ownedNoteIds) _media.DeleteNote(userId, noteId);
        DeleteUserFolder(userId);

        _logger.LogInformation("Deleted an account and its {Count} note(s)", ownedNoteIds.Count);

        await Task.WhenAll(collaborators.Distinct().Select(uid =>
            _notifier.NotifyAsync(uid, RealtimeResources.Notes, RealtimeResources.Lists, RealtimeResources.Notification)));
        await Task.WhenAll(ownersOfShared.Distinct().Select(uid => _notifier.NotifyAsync(uid, RealtimeResources.Notes)));
        await Task.WhenAll(inboxOwners.Distinct().Select(uid => _notifier.NotifyAsync(uid, RealtimeResources.Notification)));
        // The user's other devices: their next request finds no account and signs them out.
        await _notifier.NotifyAsync(userId, RealtimeResources.Account);
        return true;
    }

    /// <summary>
    /// What is left of the user's folder: the profile picture, and anything the per-note deletes
    /// missed. Best effort, as every media delete is.
    /// </summary>
    private static void DeleteUserFolder(Guid userId)
    {
        var dir = Path.Combine(FolderManagement.RootPath, "users", userId.ToString());
        try { if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true); }
        catch (IOException) { /* best-effort; the folder holds nothing a later account can reach */ }
    }
}
