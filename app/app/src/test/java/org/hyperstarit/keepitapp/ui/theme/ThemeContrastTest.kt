package org.hyperstarit.keepitapp.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Contrast guard for [KeepItColors], the Android half of the design system.
 *
 * This is the counterpart of `web/src/index.css.test.ts`, and it exists for the same reason: the
 * failure is invisible. Nothing crashes and nothing logs — a label is simply unreadable, and only
 * on the surfaces nobody screenshotted. `TextFaint` shipped at 4.39:1 on [KeepItColors.Surface] and
 * 3.84:1 on [KeepItColors.Elevated] (menus, sheets, dialogs, the Settings labels) while reading a
 * comfortable 4.97:1 on the canvas it had been picked against.
 *
 * The tokens are transcribed from the web's **dim** theme and are meant never to be re-picked by
 * eye (ARCHITECTURE.md → "UI & design parity"), so a value arriving here that fails AA means either
 * the transcription drifted or the web value itself regressed. Either way it should stop the build
 * rather than ship.
 */
class ThemeContrastTest {

    /** WCAG 2.1 AA for body text. */
    private val aaText = 4.5

    /**
     * WCAG 2.1 AA for large text and UI components. Applied to [KeepItColors.TextFaint] on the
     * per-note backgrounds only: what it carries on a coloured card is a timestamp, a "2/3" counter
     * and a reminder chip, never note content. A card's own title and body use [KeepItColors.Text]
     * and [KeepItColors.TextMuted], which are held to [aaText] on every swatch below.
     */
    private val aaLarge = 3.0

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** WCAG 2.1 contrast ratio, 1.0 to 21.0. */
    private fun contrast(a: Color, b: Color): Double {
        val high = maxOf(luminance(a), luminance(b))
        val low = minOf(luminance(a), luminance(b))
        return (high + 0.05) / (low + 0.05)
    }

    private fun assertContrast(fg: Color, bg: Color, bar: Double, what: String) {
        val ratio = contrast(fg, bg)
        assertTrue(
            "$what is %.2f:1, below the %.1f:1 bar".format(ratio, bar),
            ratio >= bar,
        )
    }

    /** The chrome a token is read against: the page, a card, and a menu or sheet. */
    private val chrome = listOf(
        "Canvas" to KeepItColors.Canvas,
        "Surface" to KeepItColors.Surface,
        "Elevated" to KeepItColors.Elevated,
    )

    @Test
    fun `every text token reaches AA on every chrome surface`() {
        val text = listOf(
            "Text" to KeepItColors.Text,
            "TextMuted" to KeepItColors.TextMuted,
            "TextFaint" to KeepItColors.TextFaint,
        )
        for ((fgName, fg) in text) {
            for ((bgName, bg) in chrome) {
                assertContrast(fg, bg, aaText, "$fgName on $bgName")
            }
        }
    }

    @Test
    fun `the accent reads as content on every chrome surface`() {
        // The accent is used as a tint or text colour at most of its call sites, not just as a
        // fill. If a light scheme is ever added as a straight token swap, this is the assertion
        // that will fail first: the bright accent is 1.67:1 on white. Splitting the token into a
        // fill and an "ink" form -- as the web did -- is the fix, not relaxing this.
        for ((bgName, bg) in chrome) {
            assertContrast(KeepItColors.Accent, bg, aaText, "Accent on $bgName")
        }
    }

    @Test
    fun `note content reads on every per-note background`() {
        for (swatch in NotePalette) {
            assertContrast(KeepItColors.Text, swatch.bg, aaText, "Text on ${swatch.key}")
            assertContrast(KeepItColors.TextMuted, swatch.bg, aaText, "TextMuted on ${swatch.key}")
            assertContrast(KeepItColors.TextFaint, swatch.bg, aaLarge, "TextFaint on ${swatch.key}")
        }
    }

    @Test
    fun `a note border is visible against its own fill`() {
        for (swatch in NotePalette) {
            val ratio = contrast(swatch.bg, swatch.border)
            assertTrue(
                "${swatch.key} border is %.3f:1 against its own fill".format(ratio),
                ratio > 1.05,
            )
        }
    }

    @Test
    fun `text tokens stay ordered from strongest to faintest`() {
        // Hierarchy, not just legibility: raising a token to clear AA must not flatten it into the
        // one above. Measured against the canvas, since that is where the ordering is widest.
        val text = contrast(KeepItColors.Text, KeepItColors.Canvas)
        val muted = contrast(KeepItColors.TextMuted, KeepItColors.Canvas)
        val faint = contrast(KeepItColors.TextFaint, KeepItColors.Canvas)
        assertTrue("Text ($text) should out-contrast TextMuted ($muted)", text > muted)
        assertTrue("TextMuted ($muted) should out-contrast TextFaint ($faint)", muted > faint)
    }
}
