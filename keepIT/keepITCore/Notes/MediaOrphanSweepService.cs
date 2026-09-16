using keepITCore.Data;
using keepITCore.Service;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// Removes note media folders with no matching note. Media writes bytes before the database row
/// (so a client never sees a row pointing at missing bytes), which means a crash between the two
/// leaves a file nothing references. This is the safety net for that, plus any folder left behind by
/// a best-effort delete that failed.
/// </summary>
public class MediaOrphanSweepService : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromHours(24);
    private static readonly TimeSpan StartupDelay = TimeSpan.FromMinutes(5);

    private readonly IServiceScopeFactory _scopes;
    private readonly IMediaStorage _storage;
    private readonly ILogger<MediaOrphanSweepService> _log;

    /// <summary>Injects the scope factory (for a scoped DbContext), storage and logger.</summary>
    /// <param name="scopes">Creates a scope per sweep so the context isn't captured by a singleton.</param>
    /// <param name="storage">Enumerates and deletes note folders.</param>
    /// <param name="log">Logger.</param>
    public MediaOrphanSweepService(
        IServiceScopeFactory scopes, IMediaStorage storage, ILogger<MediaOrphanSweepService> log)
    {
        _scopes = scopes;
        _storage = storage;
        _log = log;
    }

    /// <inheritdoc />
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Don't compete with startup; nothing here is urgent.
        try { await Task.Delay(StartupDelay, stoppingToken); }
        catch (OperationCanceledException) { return; }

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await SweepAsync(stoppingToken);
            }
            catch (Exception ex)
            {
                // Never let a sweep failure take the host down — it retries tomorrow.
                _log.LogError(ex, "Media orphan sweep failed.");
            }

            try { await Task.Delay(Interval, stoppingToken); }
            catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>Deletes every note media folder whose note no longer exists.</summary>
    /// <param name="ct">Cancellation token.</param>
    private async Task SweepAsync(CancellationToken ct)
    {
        var folders = _storage.EnumerateNoteFolders();
        if (folders.Count == 0) return;

        using var scope = _scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();

        var noteIds = folders.Select(f => f.NoteId).ToList();
        var live = await db.Notes.AsNoTracking()
            .Where(n => noteIds.Contains(n.Id))
            .Select(n => n.Id)
            .ToListAsync(ct);

        var liveSet = live.ToHashSet();
        var removed = 0;

        foreach (var (ownerId, noteId) in folders)
        {
            if (liveSet.Contains(noteId)) continue;
            _storage.DeleteNote(ownerId, noteId);
            removed++;
        }

        if (removed > 0)
            _log.LogInformation("Media orphan sweep removed {Count} folder(s).", removed);
    }
}
