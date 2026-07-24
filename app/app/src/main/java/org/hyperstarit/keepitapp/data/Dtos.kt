package org.hyperstarit.keepitapp.data

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the keepIT REST API, hand-mirrored from the C# DTOs in `keepIT/keepITCore` (the
 * single source of truth for the contract — there is no Kotlin OpenAPI generator wired up yet, so
 * when a C# DTO changes this file must be updated to match). JSON is camelCase (ASP.NET default);
 * enum-like fields (`type`, `role`) travel as their C# enum *names* thanks to the backend's
 * JsonStringEnumConverter, so they're modeled as strings here with the known values as constants.
 */

/** `NoteType` values as sent on the wire. */
object NoteTypes {
    const val TEXT = "Text"
    const val CHECKLIST = "Checklist"
}

/** `NoteRole` values as sent on the wire. */
object NoteRoles {
    const val VIEWER = "Viewer"
    const val EDITOR = "Editor"
}

/** `ReminderRecurrence` values as sent on the wire. */
object ReminderRecurrences {
    const val NONE = "None"
    const val DAILY = "Daily"
    const val WEEKLY = "Weekly"
    const val MONTHLY = "Monthly"
    const val YEARLY = "Yearly"
}

/** `NotificationType` values as sent on the wire. */
object NotificationTypes {
    const val SYSTEM = "System"
    const val SHARE_INVITE = "ShareInvite"
    const val REMINDER = "Reminder"
}

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String? = null,
)

@Serializable
data class AuthResponseDto(
    val accessToken: String,
    val accessTokenExpiresAtUtc: String,
    val user: UserDto,
)

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class RegisterRequestDto(
    val email: String,
    val password: String,
    val displayName: String? = null,
)

@Serializable
data class ForgotPasswordRequestDto(val email: String)

/** Changes the signed-in user's password; the caller comes from the access token. */
@Serializable
data class ChangePasswordRequestDto(
    val currentPassword: String,
    val newPassword: String,
)

/** Public facts about the server instance (`GET api/meta`, anonymous). */
@Serializable
data class MetaDto(val version: String = "")

@Serializable
data class ChecklistItemDto(
    val id: String? = null,
    val text: String = "",
    val isChecked: Boolean = false,
    val order: Int = 0,
)

@Serializable
data class NoteDto(
    val id: String,
    val type: String = NoteTypes.TEXT,
    val title: String? = null,
    val body: String? = null,
    val color: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    /** The caller's reminder on this note (per-user, like the flags above). Null = none set. */
    val remindAtUtc: String? = null,
    /** The reminder's recurrence ([ReminderRecurrences]); null when no reminder is set. */
    val reminderRecurrence: String? = null,
    /** True when a one-time reminder has already fired (renders as past until cleared or rescheduled). */
    val reminderFired: Boolean = false,
    val createdAtUtc: String = "",
    val updatedAtUtc: String = "",
    val isOwner: Boolean = true,
    val role: String? = null,
    val canEdit: Boolean = true,
    val isShared: Boolean = false,
    val checklistItems: List<ChecklistItemDto> = emptyList(),
    val listIds: List<String> = emptyList(),
)

@Serializable
data class CreateNoteDto(
    val type: String = NoteTypes.TEXT,
    val title: String? = null,
    val body: String? = null,
    val color: String? = null,
    val checklistItems: List<ChecklistItemDto>? = null,
    val listIds: List<String>? = null,
)

@Serializable
data class UpdateNoteDto(
    val type: String,
    val title: String? = null,
    val body: String? = null,
    val color: String? = null,
    val checklistItems: List<ChecklistItemDto>? = null,
)

@Serializable
data class NoteStateDto(
    val isPinned: Boolean? = null,
    val isArchived: Boolean? = null,
    val isTrashed: Boolean? = null,
)

@Serializable
data class SetNoteListsDto(val listIds: List<String>)

/**
 * Sets (or replaces) the caller's reminder on a note. `remindAtUtc` is an ISO-8601 UTC instant; a
 * past value is allowed — the server's dispatcher fires it on its next tick.
 */
@Serializable
data class SetNoteReminderDto(
    val remindAtUtc: String,
    val recurrence: String = ReminderRecurrences.NONE,
)

/**
 * A server-created notification from the caller's inbox (`GET api/notifications`) — the flat shape
 * with a [NotificationTypes] discriminator plus the superset of subtype fields (null when they
 * don't apply). The app surfaces active ones as native Android notifications.
 */
@Serializable
data class UserNotificationDto(
    val id: String? = null,
    val type: String = NotificationTypes.SYSTEM,
    val notificationText: String = "",
    val severity: String = "",
    val isActive: Boolean = true,
    val createdAtUtc: String = "",
    // ShareInvite-only fields.
    val sharedNoteId: String? = null,
    val sharedNoteTitle: String? = null,
    val sharedByUserEmail: String? = null,
    val role: String? = null,
    // Reminder-only fields.
    val reminderNoteId: String? = null,
    val reminderNoteTitle: String? = null,
)

@Serializable
data class ListDto(
    val id: String,
    val name: String = "",
    val color: String? = null,
    val noteCount: Int = 0,
    val createdAtUtc: String = "",
)

@Serializable
data class CreateListDto(val name: String, val color: String? = null)

/** Renames and/or recolors a list; a null field is left unchanged. */
@Serializable
data class UpdateListDto(val name: String? = null, val color: String? = null)

/**
 * One collaborator on a note (from `GET api/notes/{id}/shares`): who and at what [NoteRoles] role.
 * [pending] marks an invite that hasn't been answered yet (owner-only rows).
 */
@Serializable
data class NoteShareDto(
    val granteeId: String,
    val email: String = "",
    val role: String = NoteRoles.VIEWER,
    val createdAtUtc: String = "",
    val pending: Boolean = false,
)

/** Invites a user (by email) to collaborate on a note at a [NoteRoles] role. */
@Serializable
data class CreateShareDto(val email: String, val role: String = NoteRoles.VIEWER)

/** Changes an existing collaborator's role. */
@Serializable
data class UpdateShareRoleDto(val role: String)

/** The recipient's answer to a share invite: accept (gain access) or decline. */
@Serializable
data class ShareResponseDto(val accept: Boolean)
