package org.hyperstarit.keepitapp.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * keepIT design tokens for one theme — values copied from the web app's `web/src/index.css`, where
 * the dark theme is the `@theme` / `:root` baseline and `html[data-theme='light'|'dim']` override
 * it. The tokens are the contract between clients; never re-pick these by eye — see ARCHITECTURE.md
 * "UI & design parity". `WebTokenParityTest` reads `index.css` and fails the build if a value here
 * drifts from it.
 *
 * Screens never hold a palette themselves: they read [KeepItColors], which resolves against the
 * palette [KeepITAppTheme] provides, so switching theme is a token swap exactly like the web's. The
 * one reader outside a themed composition is the home-screen widget, which picks palettes itself
 * (see `KeepItWidget`).
 */
@Immutable
class KeepItPalette(
    /** For test messages and debugging: "dark", "dim" or "light". */
    val name: String,
    val isLight: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceHover: Color,
    val elevated: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val text: Color,
    val textMuted: Color,
    /**
     * The binding surface for this one is [elevated], not [canvas]: menus, sheets and dialogs are
     * the lightest thing in a dark theme, so a value picked against the canvas still fails on them.
     */
    val textFaint: Color,
    /** The accent as a *fill* (the FAB, filled buttons), carrying black content in every theme. */
    val accent: Color,
    val accentStrong: Color,
    /**
     * The accent as *content* — text, icons, links, the cursor. The same as [accent] on the dark
     * themes; light remaps it to a deep shade, because the bright fill is 2.9:1 on white. The web's
     * `--color-accent-ink`, and the reason Material's `primary` is this rather than [accent].
     */
    val accentInk: Color,
    /** Error text and destructive actions (Material's `error`). */
    val error: Color,
    /** The wash painted over a surface: code spans and blocks in a note body. */
    val overlayHover: Color,
    /** Per-note backgrounds, keyed like the web palette; [NoteSwatch.key] is what `Note.color` stores. */
    val notes: List<NoteSwatch>,
) {
    private val swatchByKey = notes.associateBy { it.key }

    /** Resolves a stored color key to its swatch, falling back to the default surface (like the web). */
    fun swatch(key: String?): NoteSwatch = key?.let { swatchByKey[it] } ?: notes[0]

    override fun toString(): String = "KeepItPalette($name)"

    companion object {
        /**
         * The web's dark baseline (`@theme` and `:root`): near-black canvas, the darkest of the
         * three.
         */
        val Dark = KeepItPalette(
            name = "dark",
            isLight = false,
            canvas = Color(0xFF0A0A0B),
            surface = Color(0xFF18181B),
            surfaceHover = Color(0xFF1F1F23),
            elevated = Color(0xFF202024),
            borderSubtle = Color(0xFF27272A),
            borderStrong = Color(0xFF3F3F46),
            text = Color(0xFFECECEE),
            textMuted = Color(0xFFA1A1AA),
            textFaint = Color(0xFF8A8A94),
            accent = Forest,
            accentStrong = ForestStrong,
            accentInk = Forest,
            error = DarkError,
            overlayHover = Color(0x33000000),
            notes = listOf(
                NoteSwatch("default", "Default", Color(0xFF18181B), Color(0xFF27272A)),
                NoteSwatch("rose", "Rose", Color(0xFF3B2327), Color(0xFF532F35)),
                NoteSwatch("coral", "Coral", Color(0xFF3C2A21), Color(0xFF543B2E)),
                NoteSwatch("amber", "Amber", Color(0xFF39311D), Color(0xFF4F4228)),
                NoteSwatch("sage", "Sage", Color(0xFF26342A), Color(0xFF34493B)),
                NoteSwatch("teal", "Teal", Color(0xFF1E3535), Color(0xFF294A4A)),
                NoteSwatch("sky", "Sky", Color(0xFF22323F), Color(0xFF2F4557)),
                NoteSwatch("indigo", "Indigo", Color(0xFF272C44), Color(0xFF373E5D)),
                NoteSwatch("violet", "Violet", Color(0xFF322844), Color(0xFF47385E)),
                NoteSwatch("mauve", "Mauve", Color(0xFF39263A), Color(0xFF503651)),
            ),
        )

        /**
         * `html[data-theme='dim']`: a softer dark lifted off near-black, gentler on phone OLED
         * panels than the pitch-black baseline. The Android default, and its only look before the
         * theme setting existed.
         */
        val Dim = KeepItPalette(
            name = "dim",
            isLight = false,
            canvas = Color(0xFF18181B),
            surface = Color(0xFF232327),
            surfaceHover = Color(0xFF2A2A2F),
            elevated = Color(0xFF2D2D32),
            borderSubtle = Color(0xFF323238),
            borderStrong = Color(0xFF46464D),
            text = Color(0xFFECECEE),
            textMuted = Color(0xFFB4B4BD),
            // The old 0xFF87878F read at 4.97:1 on the canvas but 4.39:1 on the surface and 3.84:1
            // on elevated — under AA for the timestamps, counters, hints and Settings labels it
            // carries.
            textFaint = Color(0xFF97979F),
            accent = Forest,
            accentStrong = ForestStrong,
            accentInk = Forest,
            error = DarkError,
            overlayHover = Color(0x33000000),
            notes = listOf(
                NoteSwatch("default", "Default", Color(0xFF232327), Color(0xFF323238)),
                NoteSwatch("rose", "Rose", Color(0xFF4A2E33), Color(0xFF5F3A42)),
                NoteSwatch("coral", "Coral", Color(0xFF4A352A), Color(0xFF5F493A)),
                NoteSwatch("amber", "Amber", Color(0xFF463D26), Color(0xFF5C4E33)),
                NoteSwatch("sage", "Sage", Color(0xFF2F4035), Color(0xFF3F594A)),
                NoteSwatch("teal", "Teal", Color(0xFF264242), Color(0xFF34595A)),
                NoteSwatch("sky", "Sky", Color(0xFF2A3E4D), Color(0xFF3A5468)),
                NoteSwatch("indigo", "Indigo", Color(0xFF313858), Color(0xFF444D72)),
                NoteSwatch("violet", "Violet", Color(0xFF3E3155), Color(0xFF564474)),
                NoteSwatch("mauve", "Mauve", Color(0xFF472F48), Color(0xFF614363)),
            ),
        )

        /**
         * `html[data-theme='light']`: near-white canvas, white cards, dark text, pastel notes. The
         * canvas sits a shade below the card so an uncolored note still reads as a card rather
         * than as bare page.
         */
        val Light = KeepItPalette(
            name = "light",
            isLight = true,
            canvas = Color(0xFFF7F7F8),
            surface = Color(0xFFFFFFFF),
            surfaceHover = Color(0xFFF0F0F2),
            elevated = Color(0xFFFFFFFF),
            borderSubtle = Color(0xFFE4E4E7),
            borderStrong = Color(0xFFD4D4D8),
            text = Color(0xFF18181B),
            textMuted = Color(0xFF52525B),
            textFaint = Color(0xFF6B6B75),
            accent = Forest,
            accentStrong = ForestStrong,
            // The app icon's own green: too deep for a fill (black on it is 3.4:1), 6.1:1 on white.
            accentInk = Color(0xFF1F6F4A),
            // The web's light `--color-danger`: the dark themes' red is 2.8:1 on white.
            error = Color(0xFF9F1239),
            overlayHover = Color(0x1218181B),
            notes = listOf(
                NoteSwatch("default", "Default", Color(0xFFFFFFFF), Color(0xFFE4E4E7)),
                NoteSwatch("rose", "Rose", Color(0xFFFAD1D7), Color(0xFFF3AAB4)),
                NoteSwatch("coral", "Coral", Color(0xFFFBDCC8), Color(0xFFF5B78F)),
                NoteSwatch("amber", "Amber", Color(0xFFFDF0B5), Color(0xFFF3DF7A)),
                NoteSwatch("sage", "Sage", Color(0xFFD6F3C6), Color(0xFFAEE79A)),
                NoteSwatch("teal", "Teal", Color(0xFFC2F5EE), Color(0xFF93E8DA)),
                NoteSwatch("sky", "Sky", Color(0xFFCFE8F7), Color(0xFFA6D3EF)),
                NoteSwatch("indigo", "Indigo", Color(0xFFD9E1FB), Color(0xFFB0C2F5)),
                NoteSwatch("violet", "Violet", Color(0xFFE6D8FB), Color(0xFFCBB0F3)),
                NoteSwatch("mauve", "Mauve", Color(0xFFF9D9F0), Color(0xFFEFB3DD)),
            ),
        )

        /** Every theme, for the tests that hold each one to the same bar. */
        val All: List<KeepItPalette> = listOf(Dark, Dim, Light)
    }
}

/** The fixed accent (forest, the green of the icon's K), like the web default. A fill in every theme. */
private val Forest = Color(0xFF41AA79)
private val ForestStrong = Color(0xFF33996A)

/**
 * Android's error red on the dark themes. It predates the web's `--color-danger` token (a paler
 * rose there) and is kept as it was; only light takes the web's value, which it needs for contrast.
 */
private val DarkError = Color(0xFFF87171)

/** The palette in effect, provided by [KeepITAppTheme]. Dim outside one, as the app looked before themes. */
val LocalKeepItPalette = staticCompositionLocalOf { KeepItPalette.Dim }

/**
 * The current theme's tokens, by the names screens have always used. Each one reads
 * [LocalKeepItPalette], so a screen restyles when the theme changes without knowing there is one —
 * the Compose analogue of the web's CSS variables.
 */
object KeepItColors {
    val Canvas: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.canvas
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.surface
    val SurfaceHover: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.surfaceHover
    val Elevated: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.elevated
    val BorderSubtle: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.borderSubtle
    val BorderStrong: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.borderStrong
    val Text: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.text
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.textMuted
    val TextFaint: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.textFaint

    /** A fill: pair it with black content. For accent-coloured text or icons use [AccentInk]. */
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.accent
    val AccentStrong: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.accentStrong

    /** The accent as text, an icon tint, a border or the cursor. See [KeepItPalette.accentInk]. */
    val AccentInk: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.accentInk
    val OverlayHover: Color @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.overlayHover
}

/** One per-note background swatch (background + border), keyed like the web palette. */
data class NoteSwatch(val key: String, val label: String, val bg: Color, val border: Color)

/**
 * The current theme's per-note palette (`--note-<key>-bg/-border`), in the order the colour picker
 * shows it. The note's `color` field stores the key (e.g. "rose"); "default" is the plain surface.
 */
val NotePalette: List<NoteSwatch> @Composable @ReadOnlyComposable get() = LocalKeepItPalette.current.notes

/** Resolves a stored color key against the current theme; see [KeepItPalette.swatch]. */
@Composable
@ReadOnlyComposable
fun noteSwatch(key: String?): NoteSwatch = LocalKeepItPalette.current.swatch(key)
