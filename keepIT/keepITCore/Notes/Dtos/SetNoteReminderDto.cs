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
}
