package org.spaceelephant.keepitapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * keepIT is dark-first (the web's baseline theme); the Material scheme is mapped straight from the
 * web tokens so both clients read as one product. No dynamic color on purpose — the palette is the
 * brand, not the wallpaper's. Dim/light arrive later as a token swap, mirroring the web.
 */
private val KeepItDarkScheme = darkColorScheme(
    primary = KeepItColors.Accent,
    onPrimary = Color.Black,
    secondary = KeepItColors.AccentStrong,
    onSecondary = Color.Black,
    background = KeepItColors.Canvas,
    onBackground = KeepItColors.Text,
    surface = KeepItColors.Surface,
    onSurface = KeepItColors.Text,
    surfaceVariant = KeepItColors.Elevated,
    onSurfaceVariant = KeepItColors.TextMuted,
    surfaceContainer = KeepItColors.Elevated,
    surfaceContainerHigh = KeepItColors.Elevated,
    surfaceContainerHighest = KeepItColors.Elevated,
    surfaceContainerLow = KeepItColors.Surface,
    surfaceContainerLowest = KeepItColors.Canvas,
    outline = KeepItColors.BorderStrong,
    outlineVariant = KeepItColors.BorderSubtle,
    error = Color(0xFFF87171),
    onError = Color.Black,
)

/** Card corner radius from the web's `--radius-card` (0.875rem ≈ 14dp). */
val CardShape = RoundedCornerShape(14.dp)

private val KeepItShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = CardShape,
    large = RoundedCornerShape(16.dp),
)

@Composable
fun KeepITAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KeepItDarkScheme,
        typography = Typography,
        shapes = KeepItShapes,
        content = content,
    )
}
