using keepITCore.Data;

namespace keepITCore.Notifications.Dtos
{
    /// <summary>
    /// A notification, in a single flat shape carrying a <see cref="Type"/> discriminator plus the
    /// superset of every subtype's fields (type-specific ones are null when they don't apply). The
    /// client switches on <see cref="Type"/> to decide what to render and which actions to offer.
    /// Also the request body when creating a <see cref="NotificationType.System"/> notification
    /// (Id, IsActive, CreatedAtUtc and the ShareInvite fields are ignored on create).
    /// </summary>
    public class UserNotificationDto
    {
        /// <summary>Notification id. Server-assigned; ignored on create.</summary>
        public Guid? Id { get; set; }

        /// <summary>Which kind of notification this is — the discriminator the client narrows on.</summary>
        public NotificationType Type { get; set; }

        /// <summary>The message shown to the user.</summary>
        public string NotificationText { get; set; } = "";

        /// <summary>Severity level: "warning", "error", or "information".</summary>
        public string Severity { get; set; } = "";

        /// <summary>Whether the notification is still active (not dismissed). Server-controlled.</summary>
        public bool IsActive { get; set; } = true;

        /// <summary>When the notification was created (UTC). Server-assigned.</summary>
        public DateTime CreatedAtUtc { get; set; }

        // ---- ShareInvite-only fields (null for every other Type) ----

        /// <summary>The note being offered. Set only when <see cref="Type"/> is ShareInvite.</summary>
        public Guid? SharedNoteId { get; set; }

        /// <summary>The offered note's title, for display. Set only for ShareInvite.</summary>
        public string? SharedNoteTitle { get; set; }

        /// <summary>The email of the user offering the note, for display. Set only for ShareInvite.</summary>
        public string? SharedByUserEmail { get; set; }
    }
}
