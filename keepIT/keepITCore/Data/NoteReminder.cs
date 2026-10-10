namespace keepITCore.Data;

/// <summary>
/// A user's reminder on a note. Like <see cref="NoteUserState"/>, this is <em>per user</em>, not
/// shared content: on a shared note each collaborator (any role — read access suffices) sets their
/// own reminder without anyone else seeing it. Composite key (<see cref="NoteId"/>, <see cref="UserId"/>) —
/// at most one reminder per user per note; row existence means "a reminder is set".
/// <para>The dispatcher scans for rows with <see cref="FiredAtUtc"/> null and <see cref="RemindAtUtc"/>
/// due, raises a <see cref="ReminderNotification"/>, and either marks the row fired (one-time) or
/// advances <see cref="RemindAtUtc"/> to the next occurrence (recurring).</para>
/// </summary>
public class NoteReminder
{
    /// <summary>The longest <see cref="TimeZone"/> kept. IANA ids run to about 30 characters.</summary>
    public const int TimeZoneMaxLength = 100;

    public Guid NoteId { get; set; }

    /// <summary>Navigation to the note the reminder is set on.</summary>
    public Note Note { get; set; } = null!;

    /// <summary>The user this reminder belongs to (and who gets the notification).</summary>
    public Guid UserId { get; set; }

    /// <summary>When to fire (UTC). For recurring reminders, always the <em>next</em> occurrence.</summary>
    public DateTime RemindAtUtc { get; set; }

    public ReminderRecurrence Recurrence { get; set; } = ReminderRecurrence.None;

    /// <summary>
    /// The IANA time zone of the device that set the reminder (<c>Europe/Vienna</c>), whose wall
    /// clock its repeats keep across daylight-saving changes. Null for a reminder set before
    /// reminders carried a zone, or by a client too old to send one; the server's own zone stands
    /// in (see <see cref="Notes.ReminderSchedule.Default"/>).
    /// </summary>
    public string? TimeZone { get; set; }

    /// <summary>
    /// The occurrence the user picked. A recurring reminder's later occurrences are all counted from
    /// it (see <see cref="Notes.ReminderSchedule"/>). Null on a reminder set before this was kept:
    /// its next occurrence fills it in from <see cref="RemindAtUtc"/>.
    /// </summary>
    public DateTime? FirstAtUtc { get; set; }

    /// <summary>
    /// Set when a one-time reminder has fired; null = still pending. Recurring reminders stay null
    /// (they advance instead). Rescheduling resets this to null.
    /// </summary>
    public DateTime? FiredAtUtc { get; set; }

    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
}
