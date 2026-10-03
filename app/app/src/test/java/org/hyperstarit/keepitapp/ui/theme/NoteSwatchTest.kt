package org.hyperstarit.keepitapp.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the per-note colour palette, in every theme.
 *
 * Both the in-app [org.hyperstarit.keepitapp.ui.notes.NoteCard] and the home-screen widget resolve
 * a note's stored key through [KeepItPalette.swatch], and the keys themselves are written by the
 * web client (`--note-<key>-bg` in `index.css`). So the lookup is a cross-client contract: a key
 * the server happily stores but this table doesn't know must degrade to the default surface, never
 * crash and never silently land on the wrong colour — and switching theme must recolour a note,
 * never re-key it.
 */
class NoteSwatchTest {

    /** The keys the web palette writes. Kept literal on purpose — deriving it from a palette would test nothing. */
    private val webKeys = listOf(
        "default", "rose", "coral", "amber", "sage", "teal", "sky", "indigo", "violet", "mauve",
    )

    @Test
    fun `every key the web can store resolves to its own swatch, in every theme`() {
        for (palette in KeepItPalette.All) {
            webKeys.forEach { key ->
                assertEquals("$palette key $key", key, palette.swatch(key).key)
            }
        }
    }

    @Test
    fun `every palette holds exactly the web's keys, in the web's order`() {
        // Order is the order the editor's colour picker renders, so a reshuffle is a visible change.
        for (palette in KeepItPalette.All) {
            assertEquals("$palette", webKeys, palette.notes.map { it.key })
        }
    }

    @Test
    fun `the themes agree on every swatch's label`() {
        // The picker names a colour, not a shade: "Rose" is rose whatever the theme.
        val labels = KeepItPalette.Dim.notes.map { it.label }
        for (palette in KeepItPalette.All) {
            assertEquals("$palette", labels, palette.notes.map { it.label })
        }
    }

    @Test
    fun `an unset colour falls back to the default surface`() {
        for (palette in KeepItPalette.All) {
            assertEquals("$palette", "default", palette.swatch(null).key)
        }
    }

    @Test
    fun `a key this build doesn't know falls back rather than throwing`() {
        // A newer web release could ship a swatch before the app catches up.
        for (palette in KeepItPalette.All) {
            assertEquals("$palette", "default", palette.swatch("chartreuse").key)
            assertEquals("$palette", "default", palette.swatch("").key)
        }
    }

    @Test
    fun `the default swatch is each theme's own card surface`() {
        // An uncoloured note is a plain card. The widget used to hardcode this as a private
        // `Surface = 0xFF232327`; if the swatch and the surface ever diverge, uncoloured widget
        // rows and cards stop matching the rest of the app.
        for (palette in KeepItPalette.All) {
            assertEquals("$palette", palette.surface, palette.swatch("default").bg)
        }
        assertEquals(Color(0xFF232327), KeepItPalette.Dim.swatch("default").bg)
    }

    @Test
    fun `every swatch is opaque, so a widget row never shows the wallpaper through it`() {
        for (palette in KeepItPalette.All) {
            palette.notes.forEach { swatch ->
                assertEquals("$palette ${swatch.key} bg alpha", 1f, swatch.bg.alpha, 0f)
                assertEquals("$palette ${swatch.key} border alpha", 1f, swatch.border.alpha, 0f)
            }
        }
    }

    @Test
    fun `no two swatches share a background`() {
        for (palette in KeepItPalette.All) {
            val backgrounds = palette.notes.map { it.bg }
            assertEquals("duplicate backgrounds in $palette", backgrounds.size, backgrounds.toSet().size)
        }
    }

    @Test
    fun `each swatch's border is distinct from its background`() {
        // The widget drops the border (Glance has no border modifier) but the app card draws it;
        // a border equal to its background would make cards read as borderless in-app only.
        for (palette in KeepItPalette.All) {
            palette.notes.forEach { swatch ->
                assertNotEquals("$palette ${swatch.key} border == bg", swatch.bg, swatch.border)
            }
        }
    }

    @Test
    fun `every swatch carries a human label for the colour picker`() {
        for (palette in KeepItPalette.All) {
            palette.notes.forEach { swatch ->
                assertTrue("$palette ${swatch.key} has no label", swatch.label.isNotBlank())
            }
        }
    }
}
