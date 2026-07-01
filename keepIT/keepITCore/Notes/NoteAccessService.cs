using keepITCore.Data;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// A caller's resolved access to a note: whether they own it and, if it's shared with them, at what
/// role. <see cref="CanEdit"/> collapses the two write paths (owner, or an Editor share).
/// </summary>
/// <param name="IsOwner">True when the caller owns the note.</param>
/// <param name="Role">The share role when the caller is a (non-owner) collaborator; null for the owner.</param>
public readonly record struct NoteAccess(bool IsOwner, NoteRole? Role)
{
    /// <summary>Owner or an Editor collaborator may change the note's content.</summary>
    public bool CanEdit => IsOwner || Role == NoteRole.Editor;
}

/// <summary>
/// The one place note authorization is decided (ARCHITECTURE.md: "Centralize this in one
/// authorization helper so no endpoint can forget it"). A caller may act on a note iff they own it
/// or hold a <see cref="NoteShare"/>; writes additionally require ownership or an Editor share.
/// </summary>
public sealed class NoteAccessService(AppDbContext db)
{
    /// <summary>Resolves the caller's access to a note.</summary>
    /// <param name="noteId">The note in question.</param>
    /// <param name="userId">The caller.</param>
    /// <returns>The <see cref="NoteAccess"/>, or null if the note doesn't exist or the caller has no access.</returns>
    public async Task<NoteAccess?> ResolveAsync(Guid noteId, Guid userId)
    {
        var owner = await db.Notes
            .AsNoTracking()
            .Where(n => n.Id == noteId)
            .Select(n => (Guid?)n.OwnerId)
            .FirstOrDefaultAsync();

        if (owner is null) return null;                 // no such note
        if (owner == userId) return new NoteAccess(true, null);

        var role = await db.NoteShares
            .AsNoTracking()
            .Where(s => s.NoteId == noteId && s.GranteeId == userId)
            .Select(s => (NoteRole?)s.Role)
            .FirstOrDefaultAsync();

        return role is null ? null : new NoteAccess(false, role);
    }

    /// <summary>
    /// The set of users whose devices should be told a note's content changed: the owner plus every
    /// grantee. Used to fan realtime signals out across a shared note (ARCHITECTURE.md "sharing-aware
    /// fan-out"), so a collaborator's edit reaches the owner and vice-versa.
    /// </summary>
    /// <param name="noteId">The note whose recipients to gather.</param>
    /// <returns>Owner id followed by each grantee id (distinct).</returns>
    public async Task<IReadOnlyList<Guid>> RecipientIdsAsync(Guid noteId)
    {
        var owner = await db.Notes
            .AsNoTracking()
            .Where(n => n.Id == noteId)
            .Select(n => (Guid?)n.OwnerId)
            .FirstOrDefaultAsync();

        if (owner is null) return [];

        var grantees = await db.NoteShares
            .AsNoTracking()
            .Where(s => s.NoteId == noteId)
            .Select(s => s.GranteeId)
            .ToListAsync();

        return grantees.Prepend(owner.Value).Distinct().ToList();
    }
}
