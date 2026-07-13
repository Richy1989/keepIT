using keepITCore.Data;
using keepITCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace keepITCore.Notes;

/// <summary>
/// Fires due <see cref="NoteReminder"/>s: every tick it scans for pending reminders whose time has
/// come, raises a <see cref="ReminderNotification"/> for the reminder's user, and pushes realtime so
/// their bell and note chips update live. One-time reminders are marked fired; recurring ones are
/// advanced to their next future occurrence (a long outage produces <em>one</em> catch-up
/// notification, not one per missed occurrence).
/// <para>Single-instance assumption: like the SignalR fan-out (see ARCHITECTURE.md's backplane
/// caveat), this does no cross-instance locking — running multiple API instances would double-fire.
/// Recurrence arithmetic is in UTC, so a daily reminder's local wall-clock time drifts an hour
/// across DST changes, and AddMonths' end-of-month clamping compounds (Jan 31 → Feb 28 → Mar 28);
/// both accepted for now (fixing them means storing the user's timezone).</para>
/// </summary>
public sealed class ReminderDispatcherService : BackgroundService
{
    private static readonly TimeSpan TickInterval = TimeSpan.FromSeconds(30);

    private readonly IServiceScopeFactory _scopeFactory;
    private readonly IRealtimeNotifier _notifier;
    private readonly ILogger<ReminderDispatcherService> _logger;

    /// <summary>Injects the scope factory (for the scoped DbContext), the notifier, and a logger.</summary>
    /// <param name="scopeFactory">Creates a DI scope per tick — <see cref="AppDbContext"/> is scoped.</param>
    /// <param name="notifier">Pushes change signals to affected users' devices (singleton).</param>
    /// <param name="logger">Dispatcher diagnostics.</param>
    public ReminderDispatcherService(
        IServiceScopeFactory scopeFactory,
        IRealtimeNotifier notifier,
        ILogger<ReminderDispatcherService> logger)
    {
        _scopeFactory = scopeFactory;
        _notifier = notifier;
        _logger = logger;
    }

    /// <inheritdoc />
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // WaitForNextTickAsync before the first scan gives startup (incl. Database.Migrate, which
        // runs inline in Program.cs before the host starts) a free grace period.
        using var timer = new PeriodicTimer(TickInterval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                await DispatchDueRemindersAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                // A failed tick (e.g. DB briefly down) must never kill the loop — try again next tick.
                _logger.LogError(ex, "Reminder dispatch tick failed");
            }
        }
    }

    /// <summary>Scans for due reminders and fires them, isolating failures per reminder.</summary>
    /// <param name="ct">Host shutdown token.</param>
    private async Task DispatchDueRemindersAsync(CancellationToken ct)
    {
        using var scope = _scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();

        var now = DateTime.UtcNow;
        var due = await db.NoteReminders
            .Include(r => r.Note)
            .Where(r => r.FiredAtUtc == null && r.RemindAtUtc <= now)
            // Trashed-for-this-user notes don't nag; a still-pending reminder fires after restore.
            .Where(r => !db.NoteUserStates.Any(us =>
                us.NoteId == r.NoteId && us.UserId == r.UserId && us.IsTrashed))
            .OrderBy(r => r.RemindAtUtc)
            .Take(100)
            .ToListAsync(ct);

        if (due.Count == 0) return;

        var affectedUsers = new HashSet<Guid>();
        foreach (var reminder in due)
        {
            try
            {
                db.Notifications.Add(new ReminderNotification
                {
                    Id = Guid.NewGuid(),
                    OwnerId = reminder.UserId,
                    Type = NotificationType.Reminder,
                    NotificationText = ComposeReminderText(reminder.Note.Title),
                    Severity = "information",
                    IsActive = true,
                    CreatedAtUtc = now,
                    ReminderNoteId = reminder.NoteId,
                    ReminderNoteTitle = reminder.Note.Title,
                });

                if (reminder.Recurrence == ReminderRecurrence.None)
                {
                    reminder.FiredAtUtc = now;
                }
                else
                {
                    // Skip occurrences missed while the server was down: one notification, then
                    // land on the first occurrence that's still in the future.
                    do
                    {
                        reminder.RemindAtUtc = Advance(reminder.RemindAtUtc, reminder.Recurrence);
                    } while (reminder.RemindAtUtc <= now);
                }

                // Save per reminder so one poison row can't roll back the whole batch.
                await db.SaveChangesAsync(ct);
                affectedUsers.Add(reminder.UserId);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Failed to fire reminder for note {NoteId}, user {UserId}",
                    reminder.NoteId, reminder.UserId);
                db.ChangeTracker.Clear();
            }
        }

        // `notification` updates the bell; `notes` refreshes the chip (fired/advanced) on all devices.
        foreach (var userId in affectedUsers)
            await _notifier.NotifyAsync(userId, RealtimeResources.Notification, RealtimeResources.Notes);
    }

    /// <summary>The next occurrence after <paramref name="from"/> for a recurrence.</summary>
    /// <param name="from">The occurrence to advance from (UTC).</param>
    /// <param name="recurrence">The repeat cadence (never <see cref="ReminderRecurrence.None"/> here).</param>
    /// <returns>The advanced timestamp.</returns>
    private static DateTime Advance(DateTime from, ReminderRecurrence recurrence) => recurrence switch
    {
        ReminderRecurrence.Daily => from.AddDays(1),
        ReminderRecurrence.Weekly => from.AddDays(7),
        ReminderRecurrence.Monthly => from.AddMonths(1),
        ReminderRecurrence.Yearly => from.AddYears(1),
        _ => throw new ArgumentOutOfRangeException(nameof(recurrence), recurrence, null),
    };

    /// <summary>A display-safe note title for reminder text.</summary>
    private static string TitleOrUntitled(string? title) =>
        string.IsNullOrWhiteSpace(title) ? "Untitled note" : title;

    /// <summary>
    /// Builds the notification text, guaranteed to fit the 200-char NotificationText column: the
    /// title (the unbounded part) is clipped first, the whole string as a belt-and-braces.
    /// </summary>
    private static string ComposeReminderText(string? title) =>
        Clip($"Reminder: \"{Clip(TitleOrUntitled(title), 60)}\"", 200);

    /// <summary>Truncates to <paramref name="max"/> chars, appending an ellipsis when clipped.</summary>
    private static string Clip(string value, int max) =>
        value.Length <= max ? value : value[..(max - 1)] + "…";
}
