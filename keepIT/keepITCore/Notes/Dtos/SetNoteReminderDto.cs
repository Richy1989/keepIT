using System.ComponentModel.DataAnnotations;
using keepITCore.Data;

namespace keepITCore.Notes.Dtos;

/// <summary>
/// Sets (or replaces) the caller's reminder on a note. A past <see cref="RemindAtUtc"/> is allowed —
/// the dispatcher simply fires it on its next tick.
/// </summary>
public class SetNoteReminderDto
{
    /// <summary>When to remind, in UTC. The server normalizes the Kind before persisting.</summary>
    public DateTime RemindAtUtc { get; set; }

    /// <summary>How often to repeat. Defaults to a one-time reminder.</summary>
    public ReminderRecurrence Recurrence { get; set; } = ReminderRecurrence.None;

    /// <summary>
    /// The IANA time zone of the device setting the reminder (<c>Europe/Vienna</c>). A repeating
    /// reminder keeps its wall-clock time there when the clocks change. Optional: a missing zone,
    /// or one the server doesn't know, falls back to the server's own.
    /// </summary>
    [MaxLength(NoteReminder.TimeZoneMaxLength)]
    public string? TimeZone { get; set; }

    /// <summary>
    /// Where a repeating series starts, when that is earlier than <see cref="RemindAtUtc"/>: a
    /// restored backup, or an offline device replaying a reminder it has since moved on. Its later
    /// occurrences are counted from here, so a monthly reminder on the 31st keeps coming back to the
    /// 31st. Defaults to <see cref="RemindAtUtc"/>, and anything later than it is ignored.
    /// </summary>
    public DateTime? FirstAtUtc { get; set; }
}
