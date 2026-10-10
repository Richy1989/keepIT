package org.hyperstarit.keepitapp.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Holds every [KeepItPalette] to the web's `index.css`, token by token.
 *
 * The tokens are the contract between the clients (ARCHITECTURE.md "UI & design parity"), and
 * Color.kt is a hand transcription of them — about seventy values across three themes, where one
 * mistyped digit is a colour nobody would notice until the two apps sit side by side. The web is
 * the canonical side, so this reads the CSS the browser reads and resolves each theme the way the
 * cascade does (the same rules as `web/src/index.css.test.ts`): `@theme` and `:root` are the dark
 * baseline, a `html[data-theme=…]` block overrides some of it, and the `forest` accent block, which
 * comes last, supplies the accent and the `--accent-ink` that light's `--color-accent-ink` points
 * at. A change on either side without the other fails here.
 */
class WebTokenParityTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the web app is a sibling. */
    private val css: String by lazy {
        val file = File("../../web/src/index.css")
        assertTrue("index.css not found at ${file.absolutePath} - run from the repository checkout", file.isFile)
        // Comments first: they quote token names and old values.
        file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "")
    }

    /** Declarations of the first block whose selector is exactly [selector]. None of these nest. */
    private fun block(selector: String): Map<String, String> {
        val start = css.indexOf("$selector {").takeIf { it >= 0 } ?: css.indexOf("$selector{")
        assertTrue("no block `$selector` in index.css", start >= 0)
        val open = css.indexOf('{', start)
        val close = css.indexOf('}', open)
        return css.substring(open + 1, close).split(';').mapNotNull { decl ->
            val colon = decl.indexOf(':')
            val name = decl.substring(0, colon.coerceAtLeast(0)).trim()
            if (colon < 0 || !name.startsWith("--")) null else name to decl.substring(colon + 1).trim()
        }.toMap()
    }

    /** The tokens in force for [theme] with the default accent, `var()`s resolved. */
    private fun resolve(theme: String): Map<String, String> {
        val merged = block("@theme") + block(":root") +
            (if (theme == "dark") emptyMap() else block("html[data-theme='$theme']")) +
            block("html[data-accent='forest']")
        fun deref(value: String, depth: Int = 0): String {
            val ref = Regex("""^var\((--[\w-]+)\)$""").find(value)?.groupValues?.get(1) ?: return value
            check(depth < 10) { "circular var(): $value" }
            return deref(checkNotNull(merged[ref]) { "unresolved var(): $ref" }, depth + 1)
        }
        return merged.mapValues { (_, v) -> deref(v) }
    }

    /** `#rrggbb` or `rgb(r g b / a)` as a Compose colour, alpha rounded the way an 8-bit channel stores it. */
    private fun parse(value: String): Color {
        Regex("""^#([0-9a-fA-F]{6})$""").find(value)?.let {
            return Color(0xFF000000 or it.groupValues[1].toLong(16))
        }
        val fn = Regex("""^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)\s*(?:[/,]\s*([\d.]+)\s*)?\)$""").find(value)
            ?: error("not a colour this test reads: $value")
        val (r, g, b) = fn.groupValues.drop(1).take(3).map { it.toInt() }
        val alpha = fn.groupValues[4].ifEmpty { "1" }.toFloat()
        return Color(r, g, b, (alpha * 255).roundToInt())
    }

    private fun assertToken(palette: KeepItPalette, token: String, actual: Color, web: Map<String, String>) {
        val value = checkNotNull(web[token]) { "index.css has no $token for $palette" }
        assertEquals("$palette: $token is $value on the web", parse(value), actual)
    }

    private val themes = mapOf(
        "dark" to KeepItPalette.Dark,
        "dim" to KeepItPalette.Dim,
        "light" to KeepItPalette.Light,
    )

    @Test
    fun `the chrome tokens match the web in every theme`() {
        for ((theme, p) in themes) {
            val web = resolve(theme)
            assertToken(p, "--color-canvas", p.canvas, web)
            assertToken(p, "--color-surface", p.surface, web)
            assertToken(p, "--color-surface-hover", p.surfaceHover, web)
            assertToken(p, "--color-elevated", p.elevated, web)
            assertToken(p, "--color-border-subtle", p.borderSubtle, web)
            assertToken(p, "--color-border-strong", p.borderStrong, web)
            assertToken(p, "--color-text", p.text, web)
            assertToken(p, "--color-text-muted", p.textMuted, web)
            assertToken(p, "--color-text-faint", p.textFaint, web)
            assertToken(p, "--color-overlay-hover", p.overlayHover, web)
        }
    }

    @Test
    fun `the accent's fill and ink forms match the web in every theme`() {
        for ((theme, p) in themes) {
            val web = resolve(theme)
            assertToken(p, "--color-accent", p.accent, web)
            assertToken(p, "--color-accent-strong", p.accentStrong, web)
            assertToken(p, "--color-accent-ink", p.accentInk, web)
        }
    }

    @Test
    fun `every per-note swatch matches the web in every theme`() {
        for ((theme, p) in themes) {
            val web = resolve(theme)
            for (swatch in p.notes) {
                assertToken(p, "--note-${swatch.key}-bg", swatch.bg, web)
                assertToken(p, "--note-${swatch.key}-border", swatch.border, web)
            }
        }
    }

    @Test
    fun `the note card recipe matches the web in every theme`() {
        for ((theme, p) in themes) {
            val web = resolve(theme)
            assertToken(p, "--color-border-control", p.borderControl, web)
            assertToken(p, "--color-overlay-lift", p.overlayLift, web)
            assertToken(p, "--color-overlay-well", p.overlayWell, web)
            assertPercent(p, "--note-lift", p.card.lift, web)
            assertPercent(p, "--note-sink", p.card.sink, web)
            assertPercent(p, "--note-ring", p.card.ring, web)
            assertEquals("$p: --shadow-note", shadow(web.getValue("--shadow-note")), p.card.shadow)
            assertToken(p, "--photo-scrim", p.card.photoScrim, web)
            assertToken(p, "--photo-text", p.card.photoText, web)
            assertToken(p, "--photo-text-muted", p.card.photoTextMuted, web)
        }
    }

    /** A `color-mix()` percentage such as `--note-lift: 50%`, as the fraction [NoteCardStyle] holds. */
    private fun assertPercent(palette: KeepItPalette, token: String, actual: Float, web: Map<String, String>) {
        val value = checkNotNull(web[token]) { "index.css has no $token for $palette" }
        assertEquals("$palette: $token is $value on the web", value.removeSuffix("%").toFloat() / 100f, actual, 1e-6f)
    }

    /** A CSS `box-shadow` list, layer by layer in the order written; lengths in px, read as dp. */
    private fun shadow(value: String): List<BoxShadow> =
        // Split on the commas between layers, not the ones inside rgb().
        value.replace(Regex("""\s+"""), " ").split(Regex(""",\s*(?![^()]*\))""")).map { layer ->
            val m = Regex("""^(inset )?((?:-?[\d.]+(?:px)? ){2,4})(rgba?\(.*\))$""").find(layer.trim())
                ?: error("not a shadow layer this test reads: $layer")
            val lengths = m.groupValues[2].trim().split(' ').map { it.removeSuffix("px").toFloat() }
            BoxShadow(
                inset = m.groupValues[1].isNotEmpty(),
                x = lengths[0],
                y = lengths[1],
                blur = lengths.getOrElse(2) { 0f },
                spread = lengths.getOrElse(3) { 0f },
                color = parse(m.groupValues[3]),
            )
        }

    @Test
    fun `light's error colour is the web's danger token`() {
        // Only light: the dark themes keep the red the app had before the web named a danger
        // token (see DarkError in Color.kt), and it passes AA there; on white it would not.
        assertToken(KeepItPalette.Light, "--color-danger", KeepItPalette.Light.error, resolve("light"))
    }
}
