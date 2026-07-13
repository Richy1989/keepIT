package org.spaceelephant.keepitapp.data.offline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.spaceelephant.keepitapp.data.CreateNoteDto
import org.spaceelephant.keepitapp.data.NoteStateDto
import org.spaceelephant.keepitapp.data.SetNoteReminderDto
import org.spaceelephant.keepitapp.data.UpdateNoteDto
import java.util.UUID

/**
 * One queued offline mutation, mirroring the five [org.spaceelephant.keepitapp.data.NotesRepository]
 * mutations 1:1. Ops are persisted in the outbox file and replayed FIFO against the same REST
 * endpoints once the server is reachable — payloads are absolute (full DTOs, not diffs), so replay
 * is idempotent and last-write-wins falls out of the backend's unconditional PUT.
 *
 * A note created offline is identified by a client-generated temp id ([Create.tempId], prefixed so
 * it can never collide with a server GUID); ops queued against it are rewritten to the real id when
 * the POST lands (see [Outbox.remapId]).
 */
@Serializable
sealed class PendingOp {
    abstract val opId: String
    abstract val enqueuedAtUtc: String

    /** The note this op targets — the temp id for a [Create], the note id for everything else. */
    val targetId: String
        get() = when (this) {
            is Create -> tempId
            is Update -> noteId
            is SetState -> noteId
            is SetLists -> noteId
            is SetReminder -> noteId
            is ClearReminder -> noteId
            is Delete -> noteId
        }

    @Serializable
    @SerialName("create")
    data class Create(
        val tempId: String,
        val dto: CreateNoteDto,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    @Serializable
    @SerialName("update")
    data class Update(
        val noteId: String,
        val dto: UpdateNoteDto,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    @Serializable
    @SerialName("setState")
    data class SetState(
        val noteId: String,
        val state: NoteStateDto,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    @Serializable
    @SerialName("setLists")
    data class SetLists(
        val noteId: String,
        val listIds: List<String>,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    @Serializable
    @SerialName("setReminder")
    data class SetReminder(
        val noteId: String,
        val dto: SetNoteReminderDto,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    @Serializable
    @SerialName("clearReminder")
    data class ClearReminder(
        val noteId: String,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    @Serializable
    @SerialName("delete")
    data class Delete(
        val noteId: String,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    companion object {
        /** Prefix marking a client-generated note id awaiting its server id. */
        const val TEMP_ID_PREFIX = "local-"

        fun newTempId(): String = TEMP_ID_PREFIX + UUID.randomUUID()

        fun newOpId(): String = UUID.randomUUID().toString()

        fun isTempId(id: String): Boolean = id.startsWith(TEMP_ID_PREFIX)
    }
}
