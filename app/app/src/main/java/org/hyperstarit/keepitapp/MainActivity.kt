package org.hyperstarit.keepitapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import org.hyperstarit.keepitapp.ui.AppRoot
import org.hyperstarit.keepitapp.ui.Destination
import org.hyperstarit.keepitapp.ui.theme.KeepITAppTheme

/**
 * Single-activity host. The home-screen widget deep-links here with intent extras — `singleTask`
 * launch mode routes taps on a running app through [onNewIntent], and [pendingDestination] carries
 * the target into the compose navigation once the session allows it.
 */
class MainActivity : ComponentActivity() {

    private val pendingDestination = mutableStateOf<Destination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light icons on both bars, always. The default picks them from the *system* theme, but the
        // app is dark whatever the phone is set to, so a phone in light mode got dark clock and
        // battery icons on a near-black bar. Transparent, because every screen draws its own
        // background edge to edge and pads for the bars itself.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        pendingDestination.value = destinationFrom(intent)
        setContent {
            KeepITAppTheme {
                AppRoot(container = appContainer, pendingDestination = pendingDestination)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destinationFrom(intent)?.let { pendingDestination.value = it }
    }

    private fun destinationFrom(intent: Intent?): Destination? = when {
        intent == null -> null
        // Text shared in from another app (ACTION_SEND, text/plain) opens the composer pre-filled.
        intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text.isNullOrBlank()) null
            else Destination.Compose(title = intent.getStringExtra(Intent.EXTRA_SUBJECT), body = text)
        }
        intent.getBooleanExtra(EXTRA_COMPOSE, false) -> Destination.Compose()
        intent.getBooleanExtra(EXTRA_INBOX, false) -> Destination.Inbox
        else -> intent.getStringExtra(EXTRA_NOTE_ID)?.let { Destination.Note(it) }
    }

    companion object {
        /** Boolean extra: open straight into the new-note composer (the widget's "+"). */
        const val EXTRA_COMPOSE = "keepit.compose"

        /** String extra: open this note in the editor (a widget note row). */
        const val EXTRA_NOTE_ID = "keepit.noteId"

        /** Boolean extra: open the in-app notification inbox (a tapped tray notification). */
        const val EXTRA_INBOX = "keepit.inbox"
    }
}
