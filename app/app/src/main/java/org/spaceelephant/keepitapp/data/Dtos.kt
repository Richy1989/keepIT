package org.spaceelephant.keepitapp.data

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

@Serializable
data class ListDto(
    val id: String,
    val name: String = "",
    val color: String? = null,
    val noteCount: Int = 0,
    val createdAtUtc: String = "",
)
