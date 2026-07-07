package org.spaceelephant.keepitapp.widget

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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import org.spaceelephant.keepitapp.MainActivity
import org.spaceelephant.keepitapp.R
import org.spaceelephant.keepitapp.data.NotesRepository
import org.spaceelephant.keepitapp.data.WidgetNote

class KeepItWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KeepItWidget()
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

// keepIT dim tokens (web index.css `data-theme='dim'`, same as KeepItColors) — Glance can't
// reference the compose theme.
private val Canvas = Color(0xFF18181B)
private val Surface = Color(0xFF232327)
private val BorderTextFaint = Color(0xFF87878F)
private val TextColor = Color(0xFFECECEE)
private val TextMuted = Color(0xFFB4B4BD)
private val Accent = Color(0xFFFBBF24)

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
            Text(
                text = "keepIT",
                modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(
                    color = ColorProvider(Accent),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
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
    Column {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(Surface))
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
