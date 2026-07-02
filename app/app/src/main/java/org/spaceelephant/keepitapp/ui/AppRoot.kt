package org.spaceelephant.keepitapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** A navigation target requested from outside the app (the home-screen widget). */
sealed interface Destination {
    data object Compose : Destination
    data class Note(val id: String) : Destination
}

/**
 * Top of the compose tree: restores the session from the refresh cookie, keeps the realtime
 * connection in lockstep with the sign-in state, and switches between login and the main nav.
 */
@Composable
fun AppRoot(container: AppContainer, pendingDestination: MutableState<Destination?>) {
    val sessionState by container.session.state.collectAsState()

    LaunchedEffect(Unit) { container.session.restore() }

    LaunchedEffect(sessionState) {
        when (sessionState) {
            is SessionState.SignedIn -> {
                container.realtime.start()
                container.notesRepo.refreshAll()
            }
            is SessionState.SignedOut -> container.realtime.stop()
            SessionState.Loading -> Unit
        }
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

    // Widget deep links: consume the pending destination once we're signed in and navigable.
    LaunchedEffect(pendingDestination.value) {
        when (val destination = pendingDestination.value) {
            Destination.Compose -> nav.navigate("editor")
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
            )
        }
        composable(
            route = "editor?noteId={noteId}",
            arguments = listOf(navArgument("noteId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { backStack ->
            EditorScreen(
                container = container,
                noteId = backStack.arguments?.getString("noteId"),
                onDone = { nav.popBackStack() },
            )
        }
    }
}
