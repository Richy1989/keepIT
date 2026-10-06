package org.hyperstarit.keepitapp.data

import java.text.BreakIterator

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

    /** A list icon, in UTF-16 units: one symbol, but a ZWJ emoji can be 11 units and a flag 14. */
    const val LIST_ICON = 16

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

    /**
     * [icon] trimmed when it is an icon the server accepts, else null. The server's rule
     * (`Lists/ListIcon.cs`): exactly one user-perceived character — so a flag or a ZWJ family is
     * one — that draws something, i.e. not whitespace, a control or a format character.
     *
     * The picker only offers icons that pass, so this guards the path that doesn't go through it:
     * a list from an imported archive, which a server refusing would take out of the outbox along
     * with every note filed in it.
     *
     * [BreakIterator] is ICU on Android, and on the JVM the unit tests run on (JDK 20+) it follows
     * the same extended grapheme cluster rules.
     */
    fun listIconOrNull(icon: String?): String? {
        val trimmed = icon?.trim()
        if (trimmed.isNullOrEmpty() || trimmed.length > LIST_ICON) return null

        val breaks = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
        breaks.first()
        if (breaks.next() != trimmed.length) return null

        // A lone surrogate reads back as itself, typed SURROGATE.
        val first = trimmed.codePointAt(0)
        val draws = !Character.isWhitespace(first) && !Character.isSpaceChar(first) &&
            Character.getType(first) !in NON_DRAWING
        return trimmed.takeIf { draws }
    }

    private val NON_DRAWING = setOf(Character.CONTROL, Character.FORMAT, Character.SURROGATE).map { it.toInt() }
}
