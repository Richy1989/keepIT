package org.hyperstarit.keepitapp.ui.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hyperstarit.keepitapp.data.NoteBackgroundNames
import org.hyperstarit.keepitapp.data.ServerFeatures
import org.hyperstarit.keepitapp.data.ServerVersions
import org.hyperstarit.keepitapp.ui.theme.LocalKeepItPalette
import org.hyperstarit.keepitapp.ui.theme.NoteSwatch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The note backgrounds this app ships: the repository's `backgrounds/` folder, copied into the
 * assets folder `backgrounds/` at build time (`copyNoteBackgrounds` in `app/build.gradle.kts`).
 * The same images, by the same names, as the web app's, so a background chosen on either shows on
 * both. Shipped rather than downloaded, so they work offline and in standalone mode.
 *
 * Each is a stencil, tiled over the note's colour from the top left ([noteBackground]) and painted
 * in that colour's ink: only the image's alpha is used, so one file suits every colour and theme.
 * A file is drawn at 2×: a 200 px wide file tiles 100 dp wide.
 */
object NoteBackgrounds {
    private const val FOLDER = "backgrounds"

    @Volatile
    private var entries: List<NoteBackgroundNames.Entry>? = null
    private val tiles = ConcurrentHashMap<String, ImageBitmap>()

    /** The backgrounds in the assets, sorted by name; read once. */
    fun all(context: Context): List<NoteBackgroundNames.Entry> = entries ?: run {
        val files = context.assets.list(FOLDER).orEmpty().toList()
        val (found, rejected) = NoteBackgroundNames.parse(files)
        // NoteBackgroundFilesTest keeps these out of a build; this only says why one is missing.
        rejected.forEach { Log.w("NoteBackgrounds", "$FOLDER/$it is not a background: see backgrounds/README.md") }
        found.also { entries = it }
    }

    /**
     * The tile for [name] at [density]: decoded and scaled once, then kept. Null for no background,
     * one this build doesn't ship (a name from a newer app), or a file that won't decode.
     * Blocking: call it off the main thread.
     */
    fun tile(context: Context, name: String?, density: Float): ImageBitmap? {
        val entry = name?.let { n -> all(context).firstOrNull { it.name == n } } ?: return null
        val file = entry.file
        val key = "$file@$density"
        tiles[key]?.let { return it }
        val decoded = runCatching {
            context.assets.open("$FOLDER/$file").use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        // Drawn at 2×: half the file's pixels in dp.
        val scale = density / 2f
        val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (width == decoded.width && height == decoded.height) decoded
        else Bitmap.createScaledBitmap(decoded, width, height, true)
        return scaled.asImageBitmap().also { tiles[key] = it }
    }
}

/**
 * Tiles the note background [name] behind the content, over whatever is drawn below it (the note's
 * colour), painted in [swatch]'s ink: its border colour at the theme's pattern strength
 * ([org.hyperstarit.keepitapp.ui.theme.NoteCardStyle.pattern]), as the web's `.note-tile` paints
 * it. Nothing for no background, or one this build doesn't ship. The tile is decoded off the main
 * thread, so it appears a frame after the card on first use.
 *
 * [scrolledBy] is how far the content is scrolled, in pixels, for a modifier placed before the
 * scroll: the tiles then move with the text, as ruled lines on paper do, while still filling the
 * whole box however short the note.
 */
@Composable
fun Modifier.noteBackground(name: String?, swatch: NoteSwatch, scrolledBy: () -> Int = { 0 }): Modifier {
    if (name == null) return this
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val ink = swatch.border.copy(alpha = swatch.border.alpha * LocalKeepItPalette.current.card.pattern)
    val tile by produceState<ImageBitmap?>(null, name, density) {
        value = withContext(Dispatchers.IO) { NoteBackgrounds.tile(context, name, density) }
    }
    val shader = remember(tile) { tile?.let { ImageShader(it, TileMode.Repeated, TileMode.Repeated) } }
    val brush = remember(shader) { shader?.let(::ShaderBrush) }
    val shift = remember { Matrix() }
    // The stencil's alpha, in the ink: SrcIn keeps the image's coverage and takes the ink's colour.
    val tint = remember(ink) { ColorFilter.tint(ink, BlendMode.SrcIn) }
    return if (shader == null || brush == null) this else drawBehind {
        shift.setTranslate(0f, -scrolledBy().toFloat())
        shader.setLocalMatrix(shift)
        drawRect(brush, colorFilter = tint)
    }
}

/** Whether the background picker is offered, and if not, whether to say why. */
enum class BackgroundsOffer { OFFERED, NEEDS_SERVER_UPDATE, HIDDEN }

/**
 * Backgrounds are offered in standalone mode, where this app is the only one that draws them, and
 * with a server that stores them ([ServerFeatures.BACKGROUNDS]). An older server would drop the
 * choice, so there the picker says the server needs updating instead; before the server's version
 * is known (never synced with it yet) the picker stays out of sight.
 */
fun backgroundsOffer(standalone: Boolean, serverVersion: String?): BackgroundsOffer = when {
    standalone -> BackgroundsOffer.OFFERED
    else -> when (ServerVersions.atLeast(serverVersion, ServerFeatures.BACKGROUNDS)) {
        true -> BackgroundsOffer.OFFERED
        false -> BackgroundsOffer.NEEDS_SERVER_UPDATE
        null -> BackgroundsOffer.HIDDEN
    }
}
