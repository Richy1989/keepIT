package org.hyperstarit.keepitapp.data

import android.app.UiModeManager
import org.hyperstarit.keepitapp.ui.theme.KeepItPalette
import org.hyperstarit.keepitapp.ui.theme.paletteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The theme setting: what it stores, what it falls back to, and what each choice shows.
 *
 * The stored keys are the web's (`UserSettings.Theme`), so a choice reads the same in both
 * clients' pickers; the fallback is what an existing install gets on the update that introduced
 * the setting, so it must be the look the app already had.
 */
class ThemePrefTest {

    @Test
    fun `the stored keys are the web's theme keys`() {
        // Kept literal: the server's AllowedThemes and the web's THEME_PREFS.
        assertEquals(listOf("light", "dim", "dark", "system"), ThemePref.entries.map { it.key })
    }

    @Test
    fun `the picker reads like the web's Appearance menu`() {
        assertEquals(listOf("Light", "Dim", "Dark", "Auto"), ThemePref.entries.map { it.label })
    }

    @Test
    fun `every choice survives a round trip through its key`() {
        for (pref in ThemePref.entries) assertEquals(pref, ThemePref.fromKey(pref.key))
    }

    @Test
    fun `nothing stored means Dim, the look before the setting existed`() {
        assertEquals(ThemePref.Dim, ThemePref.fromKey(null))
        assertSame(KeepItPalette.Dim, paletteFor(ThemePref.fromKey(null), systemDark = false))
    }

    @Test
    fun `a key this build doesn't know falls back rather than throwing`() {
        assertEquals(ThemePref.Default, ThemePref.fromKey("sepia"))
        assertEquals(ThemePref.Default, ThemePref.fromKey(""))
        assertEquals(ThemePref.Default, ThemePref.fromKey("LIGHT"))
    }

    @Test
    fun `a named theme ignores the phone's dark mode`() {
        for (systemDark in listOf(false, true)) {
            assertSame(KeepItPalette.Light, paletteFor(ThemePref.Light, systemDark))
            assertSame(KeepItPalette.Dim, paletteFor(ThemePref.Dim, systemDark))
            assertSame(KeepItPalette.Dark, paletteFor(ThemePref.Dark, systemDark))
        }
    }

    @Test
    fun `Auto follows the phone between light and dark, as the web's system choice does`() {
        assertSame(KeepItPalette.Light, paletteFor(ThemePref.System, systemDark = false))
        assertSame(KeepItPalette.Dark, paletteFor(ThemePref.System, systemDark = true))
    }

    @Test
    fun `the system's night mode agrees with the palette each choice shows`() {
        // The night mode picks the platform theme the splash and first window are drawn with; one
        // that disagreed with the palette would flash the wrong colour on every cold start.
        for (pref in ThemePref.entries) {
            val expected = when {
                pref == ThemePref.System -> UiModeManager.MODE_NIGHT_AUTO
                paletteFor(pref, systemDark = false).isLight -> UiModeManager.MODE_NIGHT_NO
                else -> UiModeManager.MODE_NIGHT_YES
            }
            assertEquals("$pref", expected, pref.nightMode)
        }
    }
}
