package org.spaceelephant.keepitapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * keepIT design tokens — values copied from the web app's `web/src/index.css` @theme dark baseline
 * (the tokens are the contract between clients; never re-pick these by eye — see ARCHITECTURE.md
 * "UI & design parity"). The dim/light themes are a later token swap, exactly like the web.
 */
object KeepItColors {
    val Canvas = Color(0xFF0A0A0B)
    val Surface = Color(0xFF18181B)
    val SurfaceHover = Color(0xFF1F1F23)
    val Elevated = Color(0xFF202024)
    val BorderSubtle = Color(0xFF27272A)
    val BorderStrong = Color(0xFF3F3F46)
    val Text = Color(0xFFECECEE)
    val TextMuted = Color(0xFFA1A1AA)
    val TextFaint = Color(0xFF71717A)
    val Accent = Color(0xFFFBBF24) // default accent (yellow), like the web default
    val AccentStrong = Color(0xFFF59E0B)
}

/** One per-note background swatch (background + border), keyed like the web palette. */
data class NoteSwatch(val key: String, val label: String, val bg: Color, val border: Color)

/**
 * Per-note background palette — dark baseline from `index.css` `:root` (`--note-<key>-bg/-border`).
 * The note's `color` field stores the key (e.g. "rose"); "default" is the plain surface.
 */
val NotePalette: List<NoteSwatch> = listOf(
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
)

private val SwatchByKey = NotePalette.associateBy { it.key }

/** Resolves a stored color key to its swatch, falling back to the default surface (like the web). */
fun noteSwatch(key: String?): NoteSwatch = key?.let { SwatchByKey[it] } ?: NotePalette[0]
