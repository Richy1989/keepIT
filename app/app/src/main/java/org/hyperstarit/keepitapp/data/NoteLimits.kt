package org.hyperstarit.keepitapp.data

/**
 * The server's size limits for notes and lists, mirrored from the `[MaxLength]` attributes on the C#
 * write DTOs (`CreateNoteDto`, `UpdateNoteDto`, `ChecklistItemDto`, `CreateListDto`), which are the
 * source of truth. Change one there, change it here.
 *
 * They matter on the phone because a note the server refuses is a note the outbox has to drop. Text
 * over a limit used to be accepted here, queued, refused on upload — and then, once the next refetch
 * replaced the cache, gone. Clamping before anything is queued means a limit can never be the
 * reason a change doesn't reach the server; the editor caps its fields as you type so the clamp is
 * only a safety net for the paths that skip it (shared-in text, an imported archive).
 *
 * Lengths are UTF-16 code units, as on the server (`string.Length`), and a cut never splits a
 * surrogate pair: an emoji at the boundary goes whole rather than leaving half a character behind.
 */
object NoteLimits {
    const val TITLE = 1_000
    const val BODY = 100_000
    const val COLOR = 32
    const val CHECKLIST_ITEMS = 500
    const val CHECKLIST_ITEM_TEXT = 2_000
    const val LIST_NAME = 100

    /** [text] cut to at most [max] UTF-16 units, without ending on half a surrogate pair. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end)
    }

    private fun String?.cutTo(max: Int): String? = this?.let { cut(it, max) }

    /** Checklist rows within the item count and each row's text within its limit. */
    fun clampItems(items: List<ChecklistItemDto>?): List<ChecklistItemDto>? = items
        ?.take(CHECKLIST_ITEMS)
        ?.map { if (it.text.length <= CHECKLIST_ITEM_TEXT) it else it.copy(text = cut(it.text, CHECKLIST_ITEM_TEXT)) }

    /** A colour the server would refuse is no colour at all, as an unknown one already renders. */
    private fun String?.colorOrNull(): String? = this?.takeIf { it.length <= COLOR }

    fun clamp(dto: CreateNoteDto): CreateNoteDto = dto.copy(
        title = dto.title.cutTo(TITLE),
        body = dto.body.cutTo(BODY),
        color = dto.color.colorOrNull(),
        checklistItems = clampItems(dto.checklistItems),
    )

    fun clamp(dto: UpdateNoteDto): UpdateNoteDto = dto.copy(
        title = dto.title.cutTo(TITLE),
        body = dto.body.cutTo(BODY),
        color = dto.color.colorOrNull(),
        checklistItems = clampItems(dto.checklistItems),
    )

    fun clampListName(name: String): String = cut(name, LIST_NAME)
}
