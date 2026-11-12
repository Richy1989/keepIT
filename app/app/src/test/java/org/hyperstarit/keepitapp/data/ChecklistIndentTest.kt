package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-level checklist model shared by the Android editor and the read-only surfaces. The web
 * client applies the same rules in `web/src/features/notes/checklist.ts`; these pin the parts that
 * would otherwise silently drift.
 */
class ChecklistIndentTest {

    private fun rows(vararg spec: Triple<String, Int, Boolean>) = spec.mapIndexed { order, (text, indent, checked) ->
        ChecklistItemDto(id = "i$order", text = text, isChecked = checked, indent = indent, order = order)
    }

    @Test
    fun `first row cannot be indented and a sub-item can be outdented`() {
        val list = rows(Triple("A", 0, false), Triple("B", 0, false))

        assertFalse(list.canIndentAt(0))
        assertEquals(list, list.withIndentAt(0, 1))

        val sub = rows(Triple("A", 0, false), Triple("B", 1, false))
        assertTrue(sub.canOutdentAt(1))
        assertEquals(listOf(0, 0), sub.withIndentAt(1, 0).map { it.indent })
    }

    @Test
    fun `a parent block indents as one level, flattening its children beside it`() {
        val list = rows(
            Triple("A", 0, false),
            Triple("B", 0, false),
            Triple("C", 1, false),
            Triple("D", 1, false),
            Triple("E", 0, false),
        )

        assertTrue(list.canIndentAt(1))
        val next = list.withIndentAt(1, 1)

        assertEquals(listOf(0, 1, 1, 1, 0), next.map { it.indent })
        assertEquals(0, next.parentIndexOf(1))
        assertEquals(0, next.parentIndexOf(2))
        assertEquals(0, next.parentIndexOf(3))
    }

    @Test
    fun `checking and unchecking a parent carries the whole group`() {
        val list = rows(Triple("A", 0, false), Triple("B", 1, false), Triple("C", 1, false))

        assertEquals(listOf(true, true, true), list.withCheckedAt(0, true).map { it.isChecked })
        val allChecked = list.map { it.copy(isChecked = true) }
        assertEquals(listOf(false, false, false), allChecked.withCheckedAt(0, false).map { it.isChecked })
    }

    @Test
    fun `a child recomputes its parent`() {
        val partial = rows(Triple("A", 0, false), Triple("B", 1, true), Triple("C", 1, false))

        assertEquals(listOf(true, true, true), partial.withCheckedAt(2, true).map { it.isChecked })
        assertEquals(listOf(false, true, false), partial.withCheckedAt(2, true).withCheckedAt(2, false).map { it.isChecked })
    }

    @Test
    fun `partial groups duplicate the parent as a heading in the completed section`() {
        val list = rows(Triple("A", 0, false), Triple("B", 1, true), Triple("C", 1, false))

        val sections = list.sections()

        assertEquals(listOf(0, 2), sections.active.map { it.index })
        assertEquals(listOf("A", "B"), sections.completed.map {
            when (it) {
                is ChecklistDisplayRow.Heading -> it.text
                is ChecklistDisplayRow.Item -> it.item.text
            }
        })
        assertTrue(sections.completed.first() is ChecklistDisplayRow.Heading)
    }

    @Test
    fun `a fully checked group has no duplicated heading`() {
        val list = rows(Triple("A", 0, true), Triple("B", 1, true), Triple("C", 1, true))

        val completed = list.sections().completed

        assertEquals(3, completed.size)
        assertTrue(completed.all { it is ChecklistDisplayRow.Item })
    }

    @Test
    fun `normalising brings the first row and orphans back to the top level`() {
        val list = rows(Triple("A", 1, false), Triple("B", 7, false), Triple("C", 0, false), Triple("D", -2, false))

        assertEquals(listOf(0, 1, 0, 0), list.normalizedIndents().map { it.indent })
    }

    @Test
    fun `a new row follows the depth of the row it comes from`() {
        val list = rows(Triple("A", 0, false), Triple("B", 1, false), Triple("C", 1, false), Triple("D", 0, false))

        assertEquals(3, list.insertionIndexAfter(0))
        assertEquals(0, list.newItemIndentAfter(0))
        assertEquals(2, list.insertionIndexAfter(1))
        assertEquals(1, list.newItemIndentAfter(1))
    }
}
