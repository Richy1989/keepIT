using System.Collections.Concurrent;
using keepITCore.Data;

namespace keepITCore.Notes;

/// <summary>
/// When a recurring reminder goes off next, worked out on the clock of the person who set it.
/// <para>A reminder is set for a wall-clock time — 08:00 on Mondays — not for an instant, so its
/// repeats are counted in its <see cref="NoteReminder.TimeZone"/>: a weekly 08:00 reminder stays at
/// 08:00 when the clocks change, which it would not if a week were simply added in UTC. And every
/// occurrence is counted from the one the user picked (<see cref="NoteReminder.FirstAtUtc"/>),
/// never from the previous occurrence, so a monthly reminder on the 31st falls on February's 28th
/// and then comes back to March's 31st instead of staying on the 28th for good.</para>
/// <para>The Android app moves reminders on by itself while it is offline, or with no server at
/// all, so it holds a copy of these rules (<c>data/offline/NoteOps.kt</c>). Both are tested against
/// the same examples, <c>keepITCore.Tests/ReminderOccurrences.json</c>, and must agree with each
/// other to the millisecond: a device and the server that disagree post the same reminder twice,
/// at two different times.</para>
/// </summary>
public static class ReminderSchedule
{
    /// <summary>Zones looked up so far, by id; null for an id this machine doesn't know.</summary>
    private static readonly ConcurrentDictionary<string, TimeZoneInfo?> Zones = new(StringComparer.Ordinal);

    /// <summary>
    /// The zone for a reminder that has none of its own: one set before reminders carried a zone, or
    /// by a client too old to send one. It is the server's own zone (in a container, whatever
    /// <c>TZ</c> says; UTC when it says nothing), by an IANA id, since that is the form the clients
    /// read it in (see <see cref="ZoneIdOf"/>).
    /// </summary>
    public static (string Id, TimeZoneInfo Zone) Default { get; } = ResolveDefault();

    /// <summary>
    /// The zone id to store for a reminder: <paramref name="id"/> when this machine knows it, else
    /// null, so the reminder falls back to <see cref="Default"/> rather than being refused. A client
    /// replaying its outbox can only drop a reminder the server turns down.
    /// </summary>
    /// <param name="id">The zone the client sent, such as <c>Europe/Vienna</c>.</param>
    /// <returns>The id to keep, or null.</returns>
    public static string? KnownZoneId(string? id) => Find(id) is null ? null : id;

    /// <summary>The zone a reminder's occurrences are counted in.</summary>
    /// <param name="id">The reminder's stored zone, or null.</param>
    /// <returns>That zone, or <see cref="Default"/> when it has none (or one no longer known).</returns>
    public static TimeZoneInfo ZoneOf(string? id) => Find(id) ?? Default.Zone;

    /// <summary>
    /// The id of the zone <see cref="ZoneOf"/> picks, as the clients are told it. It is never null
    /// for a reminder, so a device counting occurrences on its own always uses the zone the server
    /// counts in.
    /// </summary>
    /// <param name="id">The reminder's stored zone, or null.</param>
    /// <returns>That id, or <see cref="Default"/>'s.</returns>
    public static string ZoneIdOf(string? id) => Find(id) is null ? Default.Id : id!;

    /// <summary>
    /// The first occurrence strictly after <paramref name="afterUtc"/> of a reminder first set for
    /// <paramref name="firstAtUtc"/> and repeating every <paramref name="recurrence"/> on
    /// <paramref name="zone"/>'s clock. A reminder whose first occurrence is still to come returns
    /// that occurrence.
    /// </summary>
    /// <param name="firstAtUtc">The occurrence the user picked; the series counts from it.</param>
    /// <param name="zone">The zone whose wall clock the reminder keeps.</param>
    /// <param name="recurrence">The cadence; never <see cref="ReminderRecurrence.None"/>.</param>
    /// <param name="afterUtc">Usually now: the occurrence returned is the first one later than this.</param>
    /// <returns>The occurrence, in UTC.</returns>
    public static DateTime NextAfter(
        DateTime firstAtUtc, TimeZoneInfo zone, ReminderRecurrence recurrence, DateTime afterUtc)
    {
        firstAtUtc = AsUtc(firstAtUtc);
        afterUtc = AsUtc(afterUtc);
        var first = TimeZoneInfo.ConvertTimeFromUtc(firstAtUtc, zone);
        var after = TimeZoneInfo.ConvertTimeFromUtc(afterUtc, zone);

        // Jump to just before `after` rather than step from the first occurrence: a daily reminder
        // set three years ago is a thousand steps otherwise. Two short of the estimate, because an
        // occurrence moved on by a gap in the clock (see ToUtc) can land later than its date says.
        var n = Math.Max(0, Elapsed(first, after, recurrence) - 2);
        DateTime next;
        while ((next = ToUtc(Occurrence(first, recurrence, n), zone)) <= afterUtc) n++;
        return next;
    }

    /// <summary>The <paramref name="n"/>th occurrence after <paramref name="first"/>, on the wall clock.</summary>
    /// <param name="first">The first occurrence, in the reminder's zone.</param>
    /// <param name="recurrence">The cadence.</param>
    /// <param name="n">How many repeats on; 0 is <paramref name="first"/> itself.</param>
    /// <returns>The wall-clock time of that occurrence, which may not exist in the zone (see ToUtc).</returns>
    private static DateTime Occurrence(DateTime first, ReminderRecurrence recurrence, int n) => recurrence switch
    {
        ReminderRecurrence.Daily => first.AddDays(n),
        ReminderRecurrence.Weekly => first.AddDays(7 * n),
        // AddMonths and AddYears clamp to the month's last day (Jan 31 + 1 month is Feb 28), and
        // because every occurrence is counted from the first, the clamp never carries over.
        ReminderRecurrence.Monthly => first.AddMonths(n),
        ReminderRecurrence.Yearly => first.AddYears(n),
        _ => throw new ArgumentOutOfRangeException(nameof(recurrence), recurrence, null),
    };

    /// <summary>Roughly how many repeats lie between two wall-clock times; never more than there are.</summary>
    private static int Elapsed(DateTime first, DateTime after, ReminderRecurrence recurrence) => recurrence switch
    {
        ReminderRecurrence.Daily => (after.Date - first.Date).Days,
        ReminderRecurrence.Weekly => (after.Date - first.Date).Days / 7,
        ReminderRecurrence.Monthly => (after.Year - first.Year) * 12 + after.Month - first.Month,
        ReminderRecurrence.Yearly => after.Year - first.Year,
        _ => throw new ArgumentOutOfRangeException(nameof(recurrence), recurrence, null),
    };

    /// <summary>
    /// A wall-clock time in <paramref name="zone"/> as an instant, for the two moments a year when
    /// that isn't one-to-one. In the hour the clocks skip, the time moves on by the length of the
    /// gap (02:30 becomes 03:30). In the hour they repeat, it is the first of the two (summer time).
    /// Both are java.time's rules (<c>LocalDateTime.atZone</c>), which the Android app uses.
    /// </summary>
    /// <param name="local">The wall-clock time.</param>
    /// <param name="zone">Its zone.</param>
    /// <returns>The instant, in UTC.</returns>
    private static DateTime ToUtc(DateTime local, TimeZoneInfo zone)
    {
        local = DateTime.SpecifyKind(local, DateTimeKind.Unspecified);

        // In both cases the offset in force before the clocks changed is the one that applies:
        // read a day earlier, since no zone changes its clocks twice in a day.
        if (zone.IsInvalidTime(local) || zone.IsAmbiguousTime(local))
        {
            var dayBefore = DateTime.SpecifyKind(local.AddDays(-1) - zone.BaseUtcOffset, DateTimeKind.Utc);
            return DateTime.SpecifyKind(local - zone.GetUtcOffset(dayBefore), DateTimeKind.Utc);
        }

        return TimeZoneInfo.ConvertTimeToUtc(local, zone);
    }

    /// <summary>
    /// A timestamp as UTC. JSON binding can yield a Local or Unspecified Kind, and SQLite reads every
    /// DateTime back Unspecified; Npgsql insists on Utc for a <c>timestamptz</c>.
    /// </summary>
    /// <param name="value">The timestamp.</param>
    /// <returns>The same instant with Kind Utc.</returns>
    public static DateTime AsUtc(DateTime value) => value.Kind switch
    {
        DateTimeKind.Utc => value,
        DateTimeKind.Local => value.ToUniversalTime(),
        _ => DateTime.SpecifyKind(value, DateTimeKind.Utc),
    };

    /// <summary>Looks a zone up by id, once per id.</summary>
    private static TimeZoneInfo? Find(string? id)
    {
        if (string.IsNullOrWhiteSpace(id)) return null;
        return Zones.GetOrAdd(id, static key =>
            TimeZoneInfo.TryFindSystemTimeZoneById(key, out var zone) ? zone : null);
    }

    /// <summary>
    /// The server's own zone by an IANA id. Linux already names it that way; Windows names it its
    /// own way (<c>W. Europe Standard Time</c>), which the clients couldn't look up. UTC when the
    /// zone has no IANA name.
    /// </summary>
    private static (string, TimeZoneInfo) ResolveDefault()
    {
        var local = TimeZoneInfo.Local;
        if (local.HasIanaId && Find(local.Id) is { } named) return (local.Id, named);
        if (TimeZoneInfo.TryConvertWindowsIdToIanaId(local.Id, out var iana) && Find(iana) is { } converted)
            return (iana, converted);
        return ("UTC", TimeZoneInfo.Utc);
    }
}
