package org.hyperstarit.keepitapp.data.offline

import android.content.Context
import org.hyperstarit.keepitapp.data.ChecklistItemDto
import org.hyperstarit.keepitapp.data.DocumentSaver
import org.hyperstarit.keepitapp.data.NoteTypes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Keeps the text of a note change the server refused for good. [SyncEngine] drops such an op, and
 * the next refetch then replaces the phone's copy with the server's — so for a note created offline,
 * or in standalone mode, the queued op was the only copy of what the user wrote. Images already get
 * this treatment ([MediaStaging.rescueToGallery]); this is the same promise for words: a refusal may
 * cost the sync, never the text. It goes to Documents/keepIT as Markdown, the form the note was
 * written in.
 */
class NoteTextRescue(private val context: Context) {

    /**
     * Saves what a refused [PendingOp.Create] or [PendingOp.Update] carried.
     *
     * @return true once it is in Documents/keepIT; false for other ops, or a note with no text.
     */
    suspend fun rescue(op: PendingOp, nowMs: Long = System.currentTimeMillis()): Boolean {
        val markdown = when (op) {
            is PendingOp.Create -> rescuedMarkdown(op.dto.type, op.dto.title, op.dto.body, op.dto.checklistItems)
            is PendingOp.Update -> rescuedMarkdown(op.dto.type, op.dto.title, op.dto.body, op.dto.checklistItems)
            else -> null
        } ?: return false
        val title = when (op) {
            is PendingOp.Create -> op.dto.title
            is PendingOp.Update -> op.dto.title
            else -> null
        }
        return DocumentSaver.save(context, rescueFileName(title, nowMs), markdown)
    }
}

/**
 * A note's content as Markdown: the title as a heading, then the body — or, for a checklist, its
 * rows as task-list items in their stored order. Both the body and the rows are kept when a note has
 * both (the editor keeps the inactive side, see EditorScreen), the active one first.
 *
 * @return null when there is no text at all (a note of nothing but photos).
 */
fun rescuedMarkdown(type: String, title: String?, body: String?, items: List<ChecklistItemDto>?): String? {
    val checklist = items.orEmpty()
        .filter { it.text.isNotBlank() }
        .sortedBy { it.order }
        .joinToString("\n") { "- [${if (it.isChecked) "x" else " "}] ${it.text}" }
        .ifBlank { null }
    val text = body?.ifBlank { null }
    val parts = if (type == NoteTypes.CHECKLIST) listOfNotNull(checklist, text) else listOfNotNull(text, checklist)
    val heading = title?.ifBlank { null }?.let { "# $it" }
    if (heading == null && parts.isEmpty()) return null
    return (listOfNotNull(heading) + parts).joinToString("\n\n") + "\n"
}

/**
 * A file name for a rescued note: its title, shortened and stripped of what file systems refuse, then
 * a timestamp so two rescues never collide (MediaStore would otherwise number them, or refuse).
 */
fun rescueFileName(title: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val stem = title.orEmpty()
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(60)
        .ifBlank { "Note" }
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss").withZone(zone).format(Instant.ofEpochMilli(nowMs))
    return "$stem ($stamp).md"
}
