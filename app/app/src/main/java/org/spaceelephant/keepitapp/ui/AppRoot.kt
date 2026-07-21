package org.spaceelephant.keepitapp.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.spaceelephant.keepitapp.AppContainer
import org.spaceelephant.keepitapp.data.SessionState
import org.spaceelephant.keepitapp.ui.auth.LoginScreen
import org.spaceelephant.keepitapp.ui.notes.EditorScreen
import org.spaceelephant.keepitapp.ui.notes.NotesScreen
import org.spaceelephant.keepitapp.ui.notifications.NotificationsScreen
import org.spaceelephant.keepitapp.ui.settings.SettingsScreen

/** A navigation target requested from outside the app (the widget, a tray notification, a share). */
sealed interface Destination {
    /** New-note composer, optionally pre-filled from text shared in via ACTION_SEND. */
    data class Compose(val title: String? = null, val body: String? = null) : Destination
    data object Inbox : Destination
    data class Note(val id: String) : Destination
}

/**
 * Top of the compose tree: restores the session from the refresh cookie, keeps the realtime
 * connection in lockstep with the sign-in state, and switches between login and the main nav.
 */
@Composable
fun AppRoot(container: AppContainer, pendingDestination: MutableState<Destination?>) {
    val sessionState by container.session.state.collectAsState()

    // Local cache + outbox come off disk first, so an offline restore lands on real content.
    LaunchedEffect(Unit) {
        container.notesRepo.loadFromDisk()
        container.session.restore()
    }

    LaunchedEffect(sessionState) {
        when (val s = sessionState) {
            is SessionState.SignedIn -> {
                container.notesRepo.onSignedIn(s.user.id)
                container.realtime.start()
                container.syncEngine.kick()
            }
            is SessionState.SignedOut -> container.realtime.stop()
            SessionState.Loading -> Unit
        }
    }

    // Returning to the foreground is a natural moment to push queued changes / pull fresh ones.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sessionState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && sessionState is SessionState.SignedIn) {
                container.syncEngine.kick()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (sessionState) {
        SessionState.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }

        SessionState.SignedOut -> LoginScreen(session = container.session)

        is SessionState.SignedIn -> MainNav(container, pendingDestination)
    }
}

@Composable
private fun MainNav(container: AppContainer, pendingDestination: MutableState<Destination?>) {
    val nav = rememberNavController()

    // Widget deep links / shared text: consume the pending destination once we're signed in.
    LaunchedEffect(pendingDestination.value) {
        when (val destination = pendingDestination.value) {
            is Destination.Compose -> {
                val params = buildList {
                    destination.title?.takeIf { it.isNotBlank() }?.let { add("sharedTitle=${Uri.encode(it)}") }
                    destination.body?.takeIf { it.isNotBlank() }?.let { add("sharedText=${Uri.encode(it)}") }
                }
                nav.navigate("editor" + if (params.isEmpty()) "" else "?" + params.joinToString("&"))
            }
            Destination.Inbox -> nav.navigate("notifications")
            is Destination.Note -> nav.navigate("editor?noteId=${destination.id}")
            null -> Unit
        }
        pendingDestination.value = null
    }

    NavHost(navController = nav, startDestination = "notes") {
        composable("notes") {
            NotesScreen(
                container = container,
                onOpenNote = { nav.navigate("editor?noteId=$it") },
                onCompose = { nav.navigate("editor") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenNotifications = { nav.navigate("notifications") },
            )
        }
        composable("settings") {
            SettingsScreen(container = container, onBack = { nav.popBackStack() })
        }
        composable("notifications") {
            NotificationsScreen(container = container, onBack = { nav.popBackStack() })
        }
        composable(
            route = "editor?noteId={noteId}&sharedTitle={sharedTitle}&sharedText={sharedText}",
            arguments = listOf(
                navArgument("noteId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("sharedTitle") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("sharedText") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStack ->
            EditorScreen(
                container = container,
                noteId = backStack.arguments?.getString("noteId"),
                initialTitle = backStack.arguments?.getString("sharedTitle"),
                initialBody = backStack.arguments?.getString("sharedText"),
                onDone = { nav.popBackStack() },
            )
        }
    }
}
