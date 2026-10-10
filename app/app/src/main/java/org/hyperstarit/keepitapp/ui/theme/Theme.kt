package org.hyperstarit.keepitapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.hyperstarit.keepitapp.data.ThemePref

/**
 * The palette a [ThemePref] shows. Auto follows the phone between light and **dark**, the same
 * rule as the web's "system" (`SettingsProvider.resolveTheme`); Dim is only ever chosen by name.
 */
fun paletteFor(pref: ThemePref, systemDark: Boolean): KeepItPalette = when (pref) {
    ThemePref.Light -> KeepItPalette.Light
    ThemePref.Dim -> KeepItPalette.Dim
    ThemePref.Dark -> KeepItPalette.Dark
    ThemePref.System -> if (systemDark) KeepItPalette.Dark else KeepItPalette.Light
}

/**
 * The Material scheme for a palette, mapped straight from the web tokens so both clients read as
 * one product. No dynamic color on purpose — the palette is the brand, not the wallpaper's.
 *
 * Every slot a component we use reads is set, not just the obvious ones: whatever is left out
 * falls back to Material's baseline purple, which is how the drawer's selected row, the selected
 * chips and the time picker's hour/minute boxes came out lavender-grey instead of on-brand.
 */
fun colorSchemeFor(p: KeepItPalette): ColorScheme {
    // Material uses `primary` as *content* far more than as a fill — TextButton and OutlinedButton
    // labels, the focused text field, the cursor, selection handles, the date picker's "today" — so
    // it is the ink form of the accent. On the dark themes that is the accent itself; on light it
    // is the deep shade, and what sits on it turns white. Filled accent buttons that want the web's
    // bright fill and black text use [accentButtonColors] instead.
    val onInk = if (p.isLight) Color.White else Color.Black
    // The selected state — the drawer's current view, a selected chip or segment, the time
    // picker's active field — is the web's accent tint (`bg-accent/15`), but with the theme's own
    // text colour on it rather than the web's accent ink: the bright ink on a tint over Dim's
    // card and dialog colours measures 4.0–4.3:1, under AA, and the tint alone reads as selected.
    val tint = p.accent.copy(alpha = 0.15f)
    val base = if (p.isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = p.accentInk,
        onPrimary = onInk,
        primaryContainer = tint,
        onPrimaryContainer = p.text,
        // Snackbar actions sit on the inverse surface, so they want the accent's other form.
        inversePrimary = if (p.isLight) p.accent else KeepItPalette.Light.accentInk,
        secondary = if (p.isLight) p.accentInk else p.accentStrong,
        onSecondary = onInk,
        secondaryContainer = tint,
        onSecondaryContainer = p.text,
        tertiary = p.accentInk,
        onTertiary = onInk,
        tertiaryContainer = tint,
        onTertiaryContainer = p.text,
        background = p.canvas,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.elevated,
        onSurfaceVariant = p.textMuted,
        // No tonal tint: the web has none, and on light, where the surface and the dialog
        // container are both white, Material would wash every raised dialog green.
        surfaceTint = Color.Transparent,
        // Snackbars: the theme's text colour as a surface, its card colour as the text on it.
        inverseSurface = p.text,
        inverseOnSurface = p.surface,
        error = p.error,
        onError = onInk,
        errorContainer = p.error.copy(alpha = 0.12f),
        onErrorContainer = p.error,
        outline = p.borderStrong,
        outlineVariant = p.borderSubtle,
        scrim = Color.Black,
        surfaceBright = p.elevated,
        surfaceContainer = p.elevated,
        surfaceContainerHigh = p.elevated,
        surfaceContainerHighest = p.elevated,
        surfaceContainerLow = p.surface,
        surfaceContainerLowest = p.canvas,
        surfaceDim = p.canvas,
    )
}

/**
 * A filled accent button as the web draws one (`bg-accent text-black`): the bright fill with black
 * on it, in every theme. Material's default filled button is `primary`, which light makes the deep
 * ink — readable, but not the brand's button.
 */
@Composable
fun accentButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(containerColor = KeepItColors.Accent, contentColor = Color.Black)

/** Card corner radius from the web's `--radius-card` (0.875rem ≈ 14dp). */
val CardShape = RoundedCornerShape(14.dp)

/** A note card's corners, from the web's `--radius-note` (1.125rem ≈ 18dp): rounder than [CardShape]. */
val NoteCardShape = RoundedCornerShape(18.dp)

private val KeepItShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = CardShape,
    large = RoundedCornerShape(16.dp),
)

/**
 * The app's theme: resolves [pref] to a palette, provides it to [KeepItColors] and builds the
 * Material scheme from the same tokens.
 */
@Composable
fun KeepITAppTheme(pref: ThemePref, content: @Composable () -> Unit) {
    val palette = paletteFor(pref, isSystemInDarkTheme())
    val scheme = remember(palette) { colorSchemeFor(palette) }
    CompositionLocalProvider(LocalKeepItPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            shapes = KeepItShapes,
            content = content,
        )
    }
}
