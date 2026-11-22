using System.Security.Cryptography;
using System.Text;
using keepITCore.Data;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>What opening a public link came to.</summary>
public enum PublicLinkOpenResult
{
    /// <summary>The link is good and the password (if any) matched.</summary>
    Opened,

    /// <summary>No such link, or it expired, or its note is in the owner's trash: all look the same.</summary>
    NotFound,

    /// <summary>The link needs a password and none, or the wrong one, was given.</summary>
    PasswordRequired,
}

/// <summary>
/// Everything secret about public links in one place (ARCHITECTURE.md "Public links"): making
/// tokens, looking a link up by one, checking its password, and issuing and reading the short-lived
/// view tickets a visitor reads attachments and saves edits with.
/// <para>A token is 32 random bytes. The database holds its SHA-256 (what an open is looked up by)
/// and a Data Protection encryption of it (so the owner can copy the link again), never the token
/// itself. A password is hashed with Identity's PBKDF2 hasher, like an account's.</para>
/// <para>A link only opens while it is live: not expired, and its note not in its owner's trash.
/// Anything else is "not found", exactly like a token that never existed.</para>
/// </summary>
public sealed class PublicLinkService
{
    /// <summary>How long a view ticket lasts. A visitor past it opens the link again (the page does so by itself).</summary>
    public static readonly TimeSpan TicketLifetime = TimeSpan.FromHours(1);

    private readonly AppDbContext _db;
    private readonly IDataProtector _tokenProtector;
    private readonly ITimeLimitedDataProtector _ticketProtector;
    private readonly PasswordHasher<NotePublicLink> _hasher = new();

    /// <summary>Injects the context and the Data Protection provider (keys under <c>App__DataRoot</c>).</summary>
    /// <param name="db">The EF Core context.</param>
    /// <param name="protection">Encrypts the stored token copy and the view tickets.</param>
    public PublicLinkService(AppDbContext db, IDataProtectionProvider protection)
    {
        _db = db;
        _tokenProtector = protection.CreateProtector("keepIT.PublicLink.Token.v1");
        _ticketProtector = protection.CreateProtector("keepIT.PublicLink.Ticket.v1").ToTimeLimitedDataProtector();
    }

    /// <summary>A new random token, in base64url (43 characters, safe in a URL fragment).</summary>
    public static string NewToken() => Base64Url(RandomNumberGenerator.GetBytes(32));

    /// <summary>The SHA-256 of a token, as stored in <see cref="NotePublicLink.TokenHash"/>.</summary>
    public static string HashToken(string token) =>
        Convert.ToBase64String(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    /// <summary>Gives <paramref name="link"/> a fresh token: its hash, and the encrypted copy.</summary>
    /// <param name="link">The link to re-key.</param>
    public void SetToken(NotePublicLink link)
    {
        var token = NewToken();
        link.TokenHash = HashToken(token);
        link.TokenProtected = _tokenProtector.Protect(token);
    }

    /// <summary>The link's token, decrypted for its owner; null if the keys that made it are gone.</summary>
    /// <param name="link">The link.</param>
    public string? TokenOf(NotePublicLink link)
    {
        try
        {
            return _tokenProtector.Unprotect(link.TokenProtected);
        }
        catch (CryptographicException)
        {
            // The Data Protection keys were lost (a data folder restored without keys/). The link still
            // opens for whoever has it, but the owner can't be shown it: they make a new one.
            return null;
        }
    }

    /// <summary>Sets or removes the link's password.</summary>
    /// <param name="link">The link.</param>
    /// <param name="password">The new password, or null for none.</param>
    public void SetPassword(NotePublicLink link, string? password) =>
        link.PasswordHash = password is null ? null : _hasher.HashPassword(link, password);

    /// <summary>
    /// Opens a link by its token: finds it, checks it is live, checks the password.
    /// </summary>
    /// <param name="token">The token from the link.</param>
    /// <param name="password">The password the visitor gave, if any.</param>
    /// <returns>The result, and the link when it opened.</returns>
    public async Task<(PublicLinkOpenResult Result, NotePublicLink? Link)> OpenAsync(string token, string? password)
    {
        var hash = HashToken(token);
        var link = await _db.NotePublicLinks.AsNoTracking().FirstOrDefaultAsync(l => l.TokenHash == hash);
        if (link is null || !await IsLiveAsync(link)) return (PublicLinkOpenResult.NotFound, null);

        if (link.PasswordHash is not null)
        {
            if (string.IsNullOrEmpty(password)) return (PublicLinkOpenResult.PasswordRequired, null);
            var verdict = _hasher.VerifyHashedPassword(link, link.PasswordHash, password);
            if (verdict == PasswordVerificationResult.Failed) return (PublicLinkOpenResult.PasswordRequired, null);
        }

        return (PublicLinkOpenResult.Opened, link);
    }

    /// <summary>A view ticket for <paramref name="link"/> as it is now.</summary>
    /// <param name="link">The opened link.</param>
    /// <param name="expiresAtUtc">When the ticket stops working.</param>
    /// <returns>The ticket the visitor sends back as <c>X-View-Ticket</c>.</returns>
    public string IssueTicket(NotePublicLink link, out DateTime expiresAtUtc)
    {
        // Never outlive the link itself.
        var until = DateTime.UtcNow + TicketLifetime;
        if (link.ExpiresAtUtc is { } linkEnds && linkEnds < until) until = linkEnds;
        expiresAtUtc = until;
        return _ticketProtector.Protect($"{link.Id:N}|{link.Revision}", new DateTimeOffset(until, TimeSpan.Zero));
    }

    /// <summary>
    /// The link a view ticket was issued for, provided the ticket is unexpired, the link is still
    /// live and has not changed since (a new password, editing turned off or a new token all bump
    /// <see cref="NotePublicLink.Revision"/>).
    /// </summary>
    /// <param name="ticket">The <c>X-View-Ticket</c> header, if any.</param>
    /// <returns>The link, or null when the ticket opens nothing.</returns>
    public async Task<NotePublicLink?> FromTicketAsync(string? ticket)
    {
        if (string.IsNullOrWhiteSpace(ticket)) return null;

        string payload;
        try
        {
            payload = _ticketProtector.Unprotect(ticket);
        }
        catch (CryptographicException)
        {
            return null;
        }

        var parts = payload.Split('|');
        if (parts.Length != 2 || !Guid.TryParse(parts[0], out var linkId) || !int.TryParse(parts[1], out var revision))
            return null;

        var link = await _db.NotePublicLinks.AsNoTracking().FirstOrDefaultAsync(l => l.Id == linkId);
        if (link is null || link.Revision != revision || !await IsLiveAsync(link)) return null;
        return link;
    }

    /// <summary>
    /// The ids, among the notes <paramref name="ownerId"/> owns, that have a live link: what
    /// <see cref="Dtos.NoteDto.IsPublic"/> shows. Expiry is checked here; the trash is not, since a
    /// trashed note's owner still sees that it is published (it opens again once restored).
    /// </summary>
    /// <param name="ownerId">The caller.</param>
    /// <param name="noteIds">The notes in question.</param>
    public async Task<HashSet<Guid>> PublishedNoteIdsAsync(Guid ownerId, IReadOnlyCollection<Guid> noteIds)
    {
        if (noteIds.Count == 0) return new HashSet<Guid>();
        var now = DateTime.UtcNow;
        var ids = await _db.NotePublicLinks.AsNoTracking()
            .Where(l => noteIds.Contains(l.NoteId) && (l.ExpiresAtUtc == null || l.ExpiresAtUtc > now))
            .Where(l => _db.Notes.Any(n => n.Id == l.NoteId && n.OwnerId == ownerId))
            .Select(l => l.NoteId)
            .ToListAsync();
        return ids.ToHashSet();
    }

    /// <summary>True when the link opens: not expired, and its note not in its owner's trash.</summary>
    private async Task<bool> IsLiveAsync(NotePublicLink link)
    {
        if (link.IsExpired(DateTime.UtcNow)) return false;

        var trashedByOwner = await _db.NoteUserStates.AsNoTracking()
            .AnyAsync(us => us.NoteId == link.NoteId && us.IsTrashed && us.UserId == _db.Notes
                .Where(n => n.Id == link.NoteId).Select(n => n.OwnerId).FirstOrDefault());
        return !trashedByOwner;
    }

    /// <summary>URL-safe base64 without padding.</summary>
    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
