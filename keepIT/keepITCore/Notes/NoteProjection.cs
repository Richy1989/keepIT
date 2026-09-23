using keepITCore.Data;
using keepITCore.Notes.Dtos;

namespace keepITCore.Notes;

/// <summary>
/// Projects a <see cref="Note"/> entity to the <see cref="NoteDto"/> a given caller sees. Shared
/// rather than private to <see cref="NotesController"/> because the export archive is defined as
/// "the same DTOs the API already serves" — two projections would be two chances to drift, and the
/// archive would silently stop matching the wire format.
/// </summary>
public static class NoteProjection
{
    /// <summary>Projects a note entity to its client DTO for a given caller (view state + access resolved).</summary>
    /// <param name="n">The note entity (with checklist items, the caller's note-lists, and shares loaded).</param>
    /// <param name="state">The caller's per-user view state, or null (treated as all-false defaults).</param>
    /// <param name="callerId">The caller, used to resolve their view, list memberships, and role.</param>
    /// <returns>The note DTO.</returns>
    public static NoteDto ToDto(Note n, NoteUserState? state, Guid callerId)
    {
        var isOwner = n.OwnerId == callerId;
        var role = isOwner ? (NoteRole?)null : n.NoteShares.FirstOrDefault(s => s.GranteeId == callerId)?.Role;
        var reminder = n.Reminders.FirstOrDefault(r => r.UserId == callerId);

        return new NoteDto
        {
            Id = n.Id,
            Type = n.Type,
            Title = n.Title,
            Body = n.Body,
            Color = n.Color,
            IsPinned = state?.IsPinned ?? false,
            IsArchived = state?.IsArchived ?? false,
            IsTrashed = state?.IsTrashed ?? false,
            RemindAtUtc = reminder?.RemindAtUtc,
            ReminderRecurrence = reminder?.Recurrence,
            ReminderFired = reminder?.FiredAtUtc is not null,
            CreatedAtUtc = n.CreatedAtUtc,
            UpdatedAtUtc = n.UpdatedAtUtc,
            IsOwner = isOwner,
            Role = role,
            CanEdit = isOwner || role == NoteRole.Editor,
            IsShared = isOwner && n.NoteShares.Count > 0,
            ChecklistItems = n.ChecklistItems
                .OrderBy(c => c.Order)
                .Select(c => new ChecklistItemDto { Id = c.Id, Text = c.Text, IsChecked = c.IsChecked, Order = c.Order })
                .ToList(),
            Media = n.Media
                .OrderBy(m => m.Order)
                .Select(m => new NoteMediaDto
                {
                    Id = m.Id,
                    Width = m.Width,
                    Height = m.Height,
                    ByteSize = m.ByteSize,
                    Order = m.Order,
                    CreatedAtUtc = m.CreatedAtUtc,
                })
                .ToList(),
            ListIds = n.NoteLists.Where(nl => nl.UserId == callerId).Select(nl => nl.ListId).ToList(),
        };
    }
}
