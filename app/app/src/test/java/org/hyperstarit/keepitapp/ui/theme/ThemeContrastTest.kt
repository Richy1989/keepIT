package org.hyperstarit.keepitapp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Contrast guard for [KeepItPalette], the Android half of the design system, in **every** theme —
 * and for the Material scheme built from each one, since that is what most components actually
 * paint with.
 *
 * This is the counterpart of `web/src/index.css.test.ts`, and it exists for the same reason: the
 * failure is invisible. Nothing crashes and nothing logs — a label is simply unreadable, and only
 * on the surfaces nobody screenshotted. `TextFaint` shipped at 4.39:1 on Dim's surface and 3.84:1
 * on its elevated colour (menus, sheets, dialogs, the Settings labels) while reading a comfortable
 * 4.97:1 on the canvas it had been picked against.
 *
 * The tokens are transcribed from the web and are meant never to be re-picked by eye (ARCHITECTURE.md
 * → "UI & design parity"), so a value arriving here that fails AA means either the transcription
 * drifted or the web value itself regressed. Either way it should stop the build rather than ship.
 */
class ThemeContrastTest {

    /** WCAG 2.1 AA for body text. */
    private val aaText = 4.5

    /**
     * WCAG 2.1 AA for large text and UI components. Applied to `textFaint` on the per-note
     * backgrounds only: what it carries on a coloured card is a timestamp, a "2/3" counter and a
     * reminder chip, never note content. A card's own title and body use `text` and `textMuted`,
     * which are held to [aaText] on every swatch below.
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

    /** WCAG 2.1 contrast ratio, 1.0 to 21.0. A translucent [b] is measured as painted over [over]. */
    private fun contrast(a: Color, b: Color, over: Color = b): Double {
        val solid = b.compositeOver(over)
        val high = maxOf(luminance(a), luminance(solid))
        val low = minOf(luminance(a), luminance(solid))
        return (high + 0.05) / (low + 0.05)
    }

    private fun assertContrast(fg: Color, bg: Color, bar: Double, what: String, over: Color = bg) {
        val ratio = contrast(fg, bg, over)
        assertTrue(
            "$what is %.2f:1, below the %.1f:1 bar".format(ratio, bar),
            ratio >= bar,
        )
    }

    /** The chrome a token is read against: the page, a card, and a menu or sheet. */
    private fun KeepItPalette.chrome() = listOf(
        "canvas" to canvas,
        "surface" to surface,
        "elevated" to elevated,
    )

    @Test
    fun `every text token reaches AA on every chrome surface`() {
        for (p in KeepItPalette.All) {
            val text = listOf("text" to p.text, "textMuted" to p.textMuted, "textFaint" to p.textFaint)
            for ((fgName, fg) in text) {
                for ((bgName, bg) in p.chrome()) {
                    assertContrast(fg, bg, aaText, "$p $fgName on $bgName")
                }
            }
        }
    }

    @Test
    fun `the accent ink reads as content on every chrome surface`() {
        // The accent is a tint or text colour at most of its call sites, not a fill. The bright
        // fill is 2.9:1 on white, which is why light has a separate ink form at all — collapsing
        // the two back into one token is what this catches, not a value to relax it for.
        for (p in KeepItPalette.All) {
            for ((bgName, bg) in p.chrome()) {
                assertContrast(p.accentInk, bg, aaText, "$p accentInk on $bgName")
            }
        }
    }

    @Test
    fun `black reads on the accent fills`() {
        // The FAB (in the app and the widget), filled buttons and the voice-note play button are
        // black on the accent in every theme, as the web's `bg-accent text-black`. The brand green
        // itself (#1F6F4A, the icon's) is 3.4:1 under black -- it is light's ink, never a fill.
        for (p in KeepItPalette.All) {
            assertContrast(Color.Black, p.accent, aaText, "$p black on accent")
            assertContrast(Color.Black, p.accentStrong, aaText, "$p black on accentStrong")
        }
    }

    @Test
    fun `error text reaches AA on every chrome surface`() {
        // Error messages and destructive actions. The dark themes' red is 2.8:1 on white.
        for (p in KeepItPalette.All) {
            for ((bgName, bg) in p.chrome()) {
                assertContrast(p.error, bg, aaText, "$p error on $bgName")
            }
        }
    }

    @Test
    fun `note content reads on every per-note background`() {
        for (p in KeepItPalette.All) {
            for (swatch in p.notes) {
                assertContrast(p.text, swatch.bg, aaText, "$p text on ${swatch.key}")
                assertContrast(p.textMuted, swatch.bg, aaText, "$p textMuted on ${swatch.key}")
                assertContrast(p.textFaint, swatch.bg, aaLarge, "$p textFaint on ${swatch.key}")
            }
        }
    }

    @Test
    fun `accent icons read on every per-note background`() {
        // The pin and a ticked checklist item on a card are accentInk icons on the note's colour.
        for (p in KeepItPalette.All) {
            for (swatch in p.notes) {
                assertContrast(p.accentInk, swatch.bg, aaLarge, "$p accentInk on ${swatch.key}")
            }
        }
    }

    @Test
    fun `note content reads across a card's sheen`() {
        // A card is painted as a gradient (NoteCardStyle), so its text lands on more than the flat
        // colour: the top and the bottom are measured too. Lifting the dark themes' tops 5% toward
        // white took textMuted to 4.1:1 on amber, which is why they sink the bottom instead.
        for (p in KeepItPalette.All) {
            for (swatch in p.notes) {
                for ((end, fill) in listOf("top" to p.card.top(swatch.bg), "bottom" to p.card.bottom(swatch.bg))) {
                    val where = "at the $end of $p ${swatch.key}"
                    assertContrast(p.text, fill, aaText, "text $where")
                    assertContrast(p.textMuted, fill, aaText, "textMuted $where")
                    assertContrast(p.textFaint, fill, aaLarge, "textFaint $where")
                    assertContrast(p.accentInk, fill, aaLarge, "accentInk $where")
                    // An unticked box's outline is a control boundary: 3:1 (WCAG 1.4.11).
                    assertContrast(p.borderControl.compositeOver(fill), fill, aaLarge, "checkbox outline $where")
                }
            }
        }
    }

    @Test
    fun `the reminder chip reads on every per-note background`() {
        // The chip carries a time, like the faint timestamps beside it: aaLarge.
        for (p in KeepItPalette.All) {
            for (swatch in p.notes) {
                assertContrast(p.textMuted, p.overlayLift, aaLarge, "$p chip on ${swatch.key}", over = swatch.bg)
            }
        }
    }

    @Test
    fun `a photo card reads whatever the photo is`() {
        // The scrim is all that stands between the text and an unknown photo, so it is measured over
        // the two extremes: text that clears AA over pure white and over pure black clears it over
        // anything between them.
        for (p in KeepItPalette.All) {
            val onPhoto = p.onPhoto()
            for ((name, photo) in listOf("white" to Color.White, "black" to Color.Black)) {
                val fill = p.card.photoScrim.compositeOver(photo)
                val text = listOf("text" to onPhoto.text, "textMuted" to onPhoto.textMuted, "textFaint" to onPhoto.textFaint)
                for ((token, fg) in text) {
                    assertContrast(fg, fill, aaText, "$p photo card $token over a $name photo")
                }
            }
        }
    }

    @Test
    fun `a note border is visible against its own fill`() {
        for (p in KeepItPalette.All) {
            for (swatch in p.notes) {
                val ratio = contrast(swatch.bg, swatch.border)
                assertTrue(
                    "$p ${swatch.key} border is %.3f:1 against its own fill".format(ratio),
                    ratio > 1.05,
                )
            }
        }
    }

    @Test
    fun `text tokens stay ordered from strongest to faintest`() {
        // Hierarchy, not just legibility: raising a token to clear AA must not flatten it into the
        // one above. Measured against the canvas, since that is where the ordering is widest.
        for (p in KeepItPalette.All) {
            val text = contrast(p.text, p.canvas)
            val muted = contrast(p.textMuted, p.canvas)
            val faint = contrast(p.textFaint, p.canvas)
            assertTrue("$p text ($text) should out-contrast textMuted ($muted)", text > muted)
            assertTrue("$p textMuted ($muted) should out-contrast textFaint ($faint)", muted > faint)
        }
    }

    @Test
    fun `the Material scheme's content colours read on what they sit on`() {
        // Most components paint with the scheme, not the tokens: TextButton labels, the focused
        // field, the date picker's "today" are `primary` on a surface; a selected drawer row, chip
        // or segment is `onSecondaryContainer` on a translucent accent tint, and the time picker's
        // active field and AM/PM toggle the primary and tertiary pairs, over whatever surface they
        // sit on; a default filled button is `onPrimary` on `primary`; a snackbar is
        // `inverseOnSurface` and its action `inversePrimary` on `inverseSurface`.
        for (p in KeepItPalette.All) {
            val s = colorSchemeFor(p)
            assertContrast(s.primary, s.surface, aaText, "$p primary on surface")
            assertContrast(s.primary, s.background, aaText, "$p primary on background")
            assertContrast(s.onPrimary, s.primary, aaText, "$p onPrimary on primary")
            assertContrast(s.onError, s.error, aaText, "$p onError on error")
            val tinted = listOf(
                "primary" to (s.onPrimaryContainer to s.primaryContainer),
                "secondary" to (s.onSecondaryContainer to s.secondaryContainer),
                "tertiary" to (s.onTertiaryContainer to s.tertiaryContainer),
            )
            for ((role, pair) in tinted) {
                for ((bgName, under) in p.chrome()) {
                    assertContrast(
                        pair.first, pair.second, aaText,
                        "$p on${role}Container on its container over $bgName", over = under,
                    )
                }
            }
            assertContrast(s.inverseOnSurface, s.inverseSurface, aaText, "$p inverseOnSurface on inverseSurface")
            assertContrast(s.inversePrimary, s.inverseSurface, aaText, "$p inversePrimary on inverseSurface")
        }
    }
}
