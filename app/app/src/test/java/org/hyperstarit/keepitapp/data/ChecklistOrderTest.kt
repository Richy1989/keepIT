package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the checklist display rule. `order` is a row's *home* position and only display
 * puts ticked rows at the bottom — nothing in the API or the type system enforces that split, so
 * the cases that would silently break it are pinned here.
 */
class ChecklistOrderTest {

    private fun items(vararg rows: Pair<String, Boolean>) =
        rows.mapIndexed { i, (text, checked) ->
            ChecklistItemDto(id = "id-$i", text = text, isChecked = checked, order = i)
        }

    @Test
    fun `unchecked rows come first, checked ones sink to the bottom`() {
        val result = items("Milk" to true, "Eggs" to false, "Bread" to true, "Jam" to false)
            .inDisplayOrder()

        assertEquals(listOf("Eggs", "Jam", "Milk", "Bread"), result.map { it.text })
    }

    @Test
    fun `each group keeps its stored order when checked rows interleave`() {
        // A note last written by an older client: checked rows sit anywhere in the home order.
        val result = items("a" to false, "b" to true, "c" to false, "d" to true, "e" to false)
            .inDisplayOrder()

        assertEquals(listOf("a", "c", "e", "b", "d"), result.map { it.text })
    }

    @Test
    fun `unticking restores the row's original slot`() {
        val home = items("a" to false, "b" to false, "c" to false)
        val ticked = home.map { if (it.text == "b") it.copy(isChecked = true) else it }

        assertEquals(listOf("a", "c", "b"), ticked.inDisplayOrder().map { it.text })
        // Home order is untouched by the tick, so unticking puts "b" back between "a" and "c".
        assertEquals(
            listOf("a", "b", "c"),
            ticked.map { it.copy(isChecked = false) }.inDisplayOrder().map { it.text },
        )
    }

    @Test
    fun `a new row lands above the checked block`() {
        val home = items("a" to false, "b" to true) + ChecklistItemDto(text = "new", order = 2)

        assertEquals(listOf("a", "new", "b"), home.inDisplayOrder().map { it.text })
    }

    @Test
    fun `empty and all-checked lists are handled`() {
        assertEquals(emptyList<ChecklistItemDto>(), emptyList<ChecklistItemDto>().inDisplayOrder())
        assertEquals(
            listOf("a", "b"),
            items("a" to true, "b" to true).inDisplayOrder().map { it.text },
        )
    }
}
