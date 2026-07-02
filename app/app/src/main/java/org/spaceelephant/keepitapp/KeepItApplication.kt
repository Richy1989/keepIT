package org.spaceelephant.keepitapp

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.spaceelephant.keepitapp.data.ApiClient
import org.spaceelephant.keepitapp.data.NotesRepository
import org.spaceelephant.keepitapp.data.RealtimeClient
import org.spaceelephant.keepitapp.data.SessionRepository

/**
 * Process-wide wiring — a hand-rolled container instead of a DI framework (deliberate for v1: four
 * singletons don't justify Hilt). Repositories are app-scoped so their StateFlows survive
 * configuration changes; screens reach them via [appContainer].
 */
class KeepItApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor for composables/activities: `context.appContainer`. */
val Context.appContainer: AppContainer
    get() = (applicationContext as KeepItApplication).container

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val apiClient = ApiClient(context)
    val session = SessionRepository(apiClient)
    val notesRepo = NotesRepository(apiClient, context.applicationContext)

    /** SignalR pushes name what changed; refetch the matching caches (web parity). */
    val realtime = RealtimeClient(apiClient, appScope) { resources ->
        appScope.launch {
            if ("notes" in resources) notesRepo.refreshNotes()
            if ("lists" in resources) notesRepo.refreshLists()
        }
    }

    init {
        notesRepo.onUnauthorized = { session.sessionExpired() }
    }
}
