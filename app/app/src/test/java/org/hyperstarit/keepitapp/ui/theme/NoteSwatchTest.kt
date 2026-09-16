package org.hyperstarit.keepitapp.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the per-note colour palette.
 *
 * Both the in-app [org.hyperstarit.keepitapp.ui.notes.NoteCard] and the home-screen widget resolve
 * a note's stored key through [noteSwatch], and the keys themselves are written by the web client
 * (`--note-<key>-bg` in `index.css`). So the lookup is a cross-client contract: a key the server
 * happily stores but this table doesn't know must degrade to the default surface, never crash and
 * never silently land on the wrong colour.
 */
class NoteSwatchTest {

    /** The keys the web palette writes. Kept literal on purpose — deriving it from [NotePalette] would test nothing. */
    private val webKeys = listOf(
        "default", "rose", "coral", "amber", "sage", "teal", "sky", "indigo", "violet", "mauve",
    )

    @Test
    fun `every key the web can store resolves to its own swatch`() {
        webKeys.forEach { key ->
            assertEquals("key $key", key, noteSwatch(key).key)
        }
    }

    @Test
    fun `the palette holds exactly the web's keys, in the web's order`() {
        // Order is the order the editor's colour picker renders, so a reshuffle is a visible change.
        assertEquals(webKeys, NotePalette.map { it.key })
    }

    @Test
    fun `an unset colour falls back to the default surface`() {
        assertEquals("default", noteSwatch(null).key)
    }

    @Test
    fun `a key this build doesn't know falls back rather than throwing`() {
        // A newer web release could ship a swatch before the app catches up.
        assertEquals("default", noteSwatch("chartreuse").key)
        assertEquals("default", noteSwatch("").key)
    }

    @Test
    fun `the default swatch is the surface the widget used to hardcode`() {
        // KeepItWidget dropped its private `Surface = 0xFF232327` in favour of this lookup; if the
        // two ever diverge, uncoloured widget rows stop matching the app's cards.
        assertEquals(Color(0xFF232327), noteSwatch("default").bg)
    }

    @Test
    fun `every swatch is opaque, so a widget row never shows the wallpaper through it`() {
        NotePalette.forEach { swatch ->
            assertEquals("${swatch.key} bg alpha", 1f, swatch.bg.alpha, 0f)
            assertEquals("${swatch.key} border alpha", 1f, swatch.border.alpha, 0f)
        }
    }

    @Test
    fun `no two swatches share a background`() {
        val backgrounds = NotePalette.map { it.bg }
        assertEquals("duplicate backgrounds in the palette", backgrounds.size, backgrounds.toSet().size)
    }

    @Test
    fun `each swatch's border is distinct from its background`() {
        // The widget drops the border (Glance has no border modifier) but the app card draws it;
        // a border equal to its background would make cards read as borderless in-app only.
        NotePalette.forEach { swatch ->
            assertNotEquals("${swatch.key} border == bg", swatch.bg, swatch.border)
        }
    }

    @Test
    fun `every swatch carries a human label for the colour picker`() {
        NotePalette.forEach { swatch ->
            assertTrue("${swatch.key} has no label", swatch.label.isNotBlank())
        }
    }
}
