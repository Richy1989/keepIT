package org.hyperstarit.keepitapp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.hyperstarit.keepitapp.MainActivity
import org.hyperstarit.keepitapp.R
import org.hyperstarit.keepitapp.appContainer
import org.hyperstarit.keepitapp.data.NotesRepository
import org.hyperstarit.keepitapp.data.WidgetNote
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.noteSwatch

class KeepItWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KeepItWidget()

    /**
     * Fires when the first widget is placed — the moment the background refresh starts earning its
     * wakeups.
     */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncWorker.schedule(context)
    }

    /**
     * Also re-asserts the schedule here, not just in [onEnabled]. `onEnabled` only runs for the
     * *first* widget ever placed, so an install that already had one before this existed would
     * never schedule anything; the system sends APPWIDGET_UPDATE after a package update, which
     * brings those along. `KEEP` makes the repeat harmless.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetSyncWorker.schedule(context)
    }

    /** The last widget was removed; stop waking up for something nobody is looking at. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSyncWorker.cancel(context)
    }
}

/**
 * Home-screen widget: the latest notes at a glance plus a floating "+" (bottom-right, the usual
 * Android affordance) into the new-note composer — the headline reason for going native
 * (ARCHITECTURE.md). It renders from a local cache that [NotesRepository] refreshes after every
 * active-grid load, so it needs no network or auth of its own and shows the last-known notes even
 * while signed out. Styled with the keepIT dark tokens.
 */
class KeepItWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val notes = NotesRepository.readWidgetNotes(context)
        provideContent { WidgetContent(context, notes) }
    }
}

/**
 * The header refresh button. Runs a one-shot sync in the background (no activity launch). Works
 * even while the app is closed — [org.hyperstarit.keepitapp.data.ApiClient] restores the access
 * token from the persisted refresh cookie; if the session has truly expired the sync is a no-op and
 * the widget keeps its last-known notes.
 *
 * The two steps around the sync are what make the tap actually do something, and both exist because
 * a widget tap usually runs with **no UI in the process**:
 *  - `loadFromDisk` because `AppRoot` is what normally restores the cache and outbox, so without it
 *    a cold process would sync against an empty outbox and have nothing to draw if the sync failed;
 *  - `renderWidgetNow` because the repository's own re-render is debounced onto an app-scoped
 *    coroutine, and once this callback returns Android may kill the process long before that runs.
 *    Rendering here also means a *failed* sync still redraws from cache, rather than the tap looking
 *    like it did nothing at all.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = context.appContainer
        container.notesRepo.loadFromDisk()
        // A sync failure (offline, expired session) is an expected outcome here, not an error: the
        // widget just re-renders what it already had.
        runCatching { container.syncEngine.sync() }
        container.notesRepo.renderWidgetNow()
    }
}

// The widget's chrome comes straight from KeepItColors, for the same reason the per-note
// backgrounds come from NotePalette via `noteSwatch`: both are plain data (an object of compose
// Colors, a List<NoteSwatch>) rather than a MaterialTheme lookup, so a Glance composition can read
// them even though it can't reference the app's theme.
//
// These used to be five private copies of the same hex. Nothing enforced the copy, so a token
// change in Color.kt silently left the widget a shade behind — which is exactly what happened to
// TextFaint. Referencing the tokens directly is the same de-duplication the widget already made
// for the default note background (see NoteSwatchTest).
private val Canvas = KeepItColors.Canvas
private val BorderTextFaint = KeepItColors.TextFaint
private val TextColor = KeepItColors.Text
private val TextMuted = KeepItColors.TextMuted
private val Accent = KeepItColors.Accent

@androidx.compose.runtime.Composable
private fun WidgetContent(context: Context, notes: List<WidgetNote>) {
    // Outer Box stacks the note list and the floating "+": the list fills the box, the small FAB is
    // aligned bottom-end and drawn on top (Glance aligns every child by contentAlignment, but the
    // full-size list ignores it, so only the FAB is actually positioned).
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Canvas))
            .cornerRadius(16.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
            // Header: title on the left, a refresh control on the right. The refresh runs a
            // background ActionCallback (no activity launch) so tapping it pulls fresh notes and
            // re-renders the widget in place — the header/body are otherwise not tappable, so this
            // is the only way to update without opening a note.
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "keepIT",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = ColorProvider(Accent),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Refresh notes",
                    colorFilter = ColorFilter.tint(ColorProvider(TextMuted)),
                    modifier = GlanceModifier
                        .size(20.dp)
                        .clickable(actionRunCallback<RefreshAction>()),
                )
            }
            Spacer(modifier = GlanceModifier.height(8.dp))

            if (notes.isEmpty()) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No notes yet — tap + to add one.",
                        style = TextStyle(color = ColorProvider(TextMuted), fontSize = 14.sp),
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(notes, itemId = { it.id.hashCode().toLong() }) { note ->
                        NoteRow(context, note)
                    }
                    // Trailing gap so the last note clears the floating "+".
                    item { Spacer(modifier = GlanceModifier.height(52.dp)) }
                }
            }
        }

        // Floating "+" overlay — matches the in-app Material FAB: accent rounded-square (56dp, 16dp
        // corners) with the black Add glyph. The wrapper's padding is the margin from the corner.
        Box(modifier = GlanceModifier.padding(end = 12.dp, bottom = 12.dp)) {
            Box(
                modifier = GlanceModifier
                    .size(56.dp)
                    .background(ColorProvider(Accent))
                    .cornerRadius(16.dp)
                    .clickable(actionStartActivity(composeIntent(context))),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_add),
                    contentDescription = "New note",
                    colorFilter = ColorFilter.tint(ColorProvider(Color.Black)),
                    modifier = GlanceModifier.size(24.dp),
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun NoteRow(context: Context, note: WidgetNote) {
    // Same swatch the in-app NoteCard fills with, so a note reads as "the yellow one" on the home
    // screen too. Only the background: Glance has no border modifier, so the card's 1dp
    // swatch.border is dropped rather than faked with a nested Box. An unset/unknown key falls
    // back to NotePalette[0] — the plain surface this row used to hardcode.
    val swatch = noteSwatch(note.color)
    Column {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(swatch.bg))
                .cornerRadius(10.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(actionStartActivity(noteIntent(context, note.id))),
        ) {
            if (note.title.isNotBlank()) {
                Text(
                    text = note.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(TextColor),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            // Checklist items each on their own line (not run together).
            note.checklist.forEach { line ->
                Text(
                    text = line,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(TextMuted), fontSize = 14.sp),
                )
            }
            if (note.preview.isNotBlank()) {
                Text(
                    text = note.preview,
                    maxLines = if (note.title.isBlank()) 2 else 1,
                    style = TextStyle(color = ColorProvider(TextMuted), fontSize = 14.sp),
                )
            }
            if (note.title.isBlank() && note.preview.isBlank() && note.checklist.isEmpty()) {
                Text(
                    text = "Empty note",
                    style = TextStyle(color = ColorProvider(BorderTextFaint), fontSize = 14.sp),
                )
            }
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
    }
}

/**
 * Each PendingIntent needs a distinct `data` URI — extras alone don't differentiate them, and the
 * launcher would otherwise reuse one intent for every tap target.
 */
private fun composeIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = "keepit://compose".toUri()
        putExtra(MainActivity.EXTRA_COMPOSE, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun noteIntent(context: Context, noteId: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = "keepit://note/$noteId".toUri()
        putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
