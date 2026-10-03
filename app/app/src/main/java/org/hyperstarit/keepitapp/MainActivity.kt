package org.hyperstarit.keepitapp

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import org.hyperstarit.keepitapp.ui.AppRoot
import org.hyperstarit.keepitapp.ui.Destination
import org.hyperstarit.keepitapp.ui.theme.KeepITAppTheme
import org.hyperstarit.keepitapp.ui.theme.KeepItPalette
import org.hyperstarit.keepitapp.ui.theme.paletteFor

/**
 * Single-activity host. The home-screen widget deep-links here with intent extras — `singleTask`
 * launch mode routes taps on a running app through [onNewIntent], and [pendingDestination] carries
 * the target into the compose navigation once the session allows it.
 */
class MainActivity : ComponentActivity() {

    private val pendingDestination = mutableStateOf<Destination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearance = appContainer.appearance
        // Before the first frame, so the bars are right from the start rather than one frame later.
        applySystemChrome(currentPalette())
        pendingDestination.value = destinationFrom(intent)
        setContent {
            val pref by appearance.theme.collectAsState()
            val palette = paletteFor(pref, isSystemInDarkTheme())
            DisposableEffect(palette) {
                applySystemChrome(palette)
                onDispose {}
            }
            KeepITAppTheme(pref) {
                AppRoot(container = appContainer, pendingDestination = pendingDestination)
            }
        }
    }

    /**
     * Re-styles the bars after the platform has handled a configuration change. A night-mode flip
     * (the phone's dark mode, or a theme picked in Settings, which changes the app's night mode)
     * makes the platform re-derive the bar icons from the window theme, *after* Compose has
     * already set them — and on Android 16 it drops the app's choice outright, leaving white icons
     * on Light. Posted, so it lands once that handling is done.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { applySystemChrome(currentPalette()) }
    }

    /** The palette on screen right now: the stored choice, resolved against this configuration. */
    private fun currentPalette(): KeepItPalette =
        paletteFor(appContainer.appearance.theme.value, resources.configuration.isNightModeActive)

    /**
     * The bars and the window behind the UI, for the theme on screen. The bar icons follow the
     * *app's* theme, not the phone's: by default they come from the system theme, so a phone in
     * light mode got dark clock and battery icons on Dim's near-black bar, and Light on a dark phone
     * would get white ones on white. Transparent, because every screen draws its own background
     * edge to edge and pads for the bars itself. The window colour shows only where Compose has not
     * drawn yet — the first frame, and behind the keyboard as it animates.
     */
    private fun applySystemChrome(palette: KeepItPalette) {
        val bars = if (palette.isLight) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        window.setBackgroundDrawable(ColorDrawable(palette.canvas.toArgb()))
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
