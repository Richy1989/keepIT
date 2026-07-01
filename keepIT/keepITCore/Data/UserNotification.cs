namespace keepITCore.Data
{
    /// <summary>
    /// Base type for a notification belonging to one user. Mapped Table-Per-Hierarchy: every subtype
    /// shares the one <c>Notifications</c> table, with <see cref="Type"/> as the discriminator and each
    /// subtype's extra fields stored as nullable columns. A user can have many; each is created and
    /// deleted explicitly via the notifications endpoints.
    /// </summary>
    public abstract class UserNotification
    {
        /// <summary>Primary key.</summary>
        public Guid Id { get; set; }

        /// <summary>The recipient — the user this notification is shown to. Every query is owner-scoped.</summary>
        public Guid OwnerId { get; set; }

        /// <summary>Navigation to the owning (recipient) user.</summary>
        public ApplicationUser Owner { get; set; } = null!;

        /// <summary>The concrete kind of notification; also the TPH discriminator column.</summary>
        public NotificationType Type { get; set; }

        /// <summary>The message shown to the user.</summary>
        public string NotificationText { get; set; } = "";

        /// <summary>Severity level (e.g. warning, error, information); drives how it's styled.</summary>
        public string Severity { get; set; } = "";

        /// <summary>Whether the notification is still active (not dismissed).</summary>
        public bool IsActive { get; set; } = true;

        /// <summary>When the notification was created (UTC); the list is shown newest-first.</summary>
        public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
    }
}
