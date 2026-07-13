namespace keepITCore.Data
{
    /// <summary>
    /// Raised by the reminder dispatcher when a user's <see cref="NoteReminder"/> comes due.
    /// <see cref="ReminderNoteTitle"/> is a denormalized point-in-time snapshot (like
    /// <see cref="ShareInviteNotification"/>'s display fields) so the notification renders without
    /// joins and stays meaningful even if the note is later renamed or deleted. Dismiss-only.
    /// </summary>
    public sealed class ReminderNotification : UserNotification
    {
        /// <summary>The note the reminder was set on. Plain id — the note may be gone by the time it's read.</summary>
        public Guid ReminderNoteId { get; set; }

        /// <summary>The note's title when the reminder fired, shown in the inbox.</summary>
        public string? ReminderNoteTitle { get; set; }
    }
}
