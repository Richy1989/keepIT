package org.hyperstarit.keepitapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * keepIT design tokens — values copied from the web app's `web/src/index.css` **dim** theme
 * (`html[data-theme='dim']`): a softer dark lifted off near-black, gentler on phone OLED panels
 * than the pitch-black baseline. The tokens are the contract between clients; never re-pick these
 * by eye — see ARCHITECTURE.md "UI & design parity". Other themes are a later token swap, exactly
 * like the web.
 */
object KeepItColors {
    val Canvas = Color(0xFF18181B)
    val Surface = Color(0xFF232327)
    val SurfaceHover = Color(0xFF2A2A2F)
    val Elevated = Color(0xFF2D2D32)
    val BorderSubtle = Color(0xFF323238)
    val BorderStrong = Color(0xFF46464D)
    val Text = Color(0xFFECECEE)
    val TextMuted = Color(0xFFB4B4BD)
    val TextFaint = Color(0xFF87878F)
    val Accent = Color(0xFFFBBF24) // default accent (yellow), like the web default
    val AccentStrong = Color(0xFFF59E0B)
}

/** One per-note background swatch (background + border), keyed like the web palette. */
data class NoteSwatch(val key: String, val label: String, val bg: Color, val border: Color)

/**
 * Per-note background palette — dim theme from `index.css` `html[data-theme='dim']`
 * (`--note-<key>-bg/-border`). The note's `color` field stores the key (e.g. "rose"); "default"
 * is the plain surface.
 */
val NotePalette: List<NoteSwatch> = listOf(
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
)

private val SwatchByKey = NotePalette.associateBy { it.key }

/** Resolves a stored color key to its swatch, falling back to the default surface (like the web). */
fun noteSwatch(key: String?): NoteSwatch = key?.let { SwatchByKey[it] } ?: NotePalette[0]
