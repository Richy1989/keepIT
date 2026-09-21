package org.hyperstarit.keepitapp.data.offline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.hyperstarit.keepitapp.data.CreateListDto
import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.SetNoteReminderDto
import org.hyperstarit.keepitapp.data.UpdateListDto
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import java.util.UUID

/**
 * One queued offline mutation, mirroring the [org.hyperstarit.keepitapp.data.NotesRepository]
 * mutations 1:1 — the note ops (create, update, set-state, set-lists, set-reminder,
 * clear-reminder, delete, attach/delete media) and the list ops (create, rename, delete). Ops are
 * persisted in the outbox file and replayed FIFO against the same REST endpoints once the server is
 * reachable — payloads are absolute (full DTOs, not diffs), so replay is idempotent and
 * last-write-wins falls out of the backend's unconditional PUT.
 *
 * A note or list created offline is identified by a client-generated temp id ([Create.tempId],
 * [CreateList.tempId], prefixed so it can never collide with a server GUID); ops queued against it
 * are rewritten to the real id when the POST lands (see [Outbox.remapId] and [Outbox.remapListId]).
 *
 * In standalone mode the queue never drains: it is the complete record of everything made on this
 * device, kept so that connecting to a server later can replay it into an account.
 */
@Serializable
sealed class PendingOp {
    abstract val opId: String
    abstract val enqueuedAtUtc: String

    /**
     * What this op targets — the temp id for a [Create] or [CreateList], the note id for the other
     * note ops, the list id for the other list ops. Note and list ids never collide (server GUIDs
     * and random temp ids alike), so one field serves both.
     */
    val targetId: String
        get() = when (this) {
            is Create -> tempId
            is Update -> noteId
            is SetState -> noteId
            is SetLists -> noteId
            is SetReminder -> noteId
            is ClearReminder -> noteId
            is Delete -> noteId
            is AttachMedia -> noteId
            is DeleteMedia -> noteId
            is CreateList -> tempId
            is UpdateList -> listId
            is DeleteList -> listId
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

    /**
     * Attaches an image to a note. Unlike every other op this one references a *file*:
     * [stagedPath] points at an app-private copy made when the user picked the image.
     *
     * A `content://` grant is revocable and dies on reboot, while this op routinely outlives both —
     * so the bytes must be ours before the op is queued. The staged file is deleted once the upload
     * lands or fails for good (see [SyncEngine]).
     */
    @Serializable
    @SerialName("attachMedia")
    data class AttachMedia(
        val noteId: String,
        val stagedPath: String,
        val tempMediaId: String,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    /** Removes an image from a note. */
    @Serializable
    @SerialName("deleteMedia")
    data class DeleteMedia(
        val noteId: String,
        val mediaId: String,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    /**
     * Creates a list under a temp id. Notes can be filed into it straight away: their queued ops
     * carry the temp id until this op replays and [Outbox.remapListId] swaps in the server's.
     */
    @Serializable
    @SerialName("createList")
    data class CreateList(
        val tempId: String,
        val dto: CreateListDto,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    /** Renames and/or recolors a list; a null field in [dto] is left unchanged, as on the server. */
    @Serializable
    @SerialName("updateList")
    data class UpdateList(
        val listId: String,
        val dto: UpdateListDto,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    /** Deletes a list. The notes filed in it survive; only their membership goes. */
    @Serializable
    @SerialName("deleteList")
    data class DeleteList(
        val listId: String,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    companion object {
        /** Prefix marking a client-generated note or list id awaiting its server id. */
        const val TEMP_ID_PREFIX = "local-"

        fun newTempId(): String = TEMP_ID_PREFIX + UUID.randomUUID()

        fun newOpId(): String = UUID.randomUUID().toString()

        fun isTempId(id: String): Boolean = id.startsWith(TEMP_ID_PREFIX)
    }
}
