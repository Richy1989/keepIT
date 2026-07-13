package org.spaceelephant.keepitapp

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.spaceelephant.keepitapp.data.ApiClient
import org.spaceelephant.keepitapp.data.NotesRepository
import org.spaceelephant.keepitapp.data.RealtimeClient
import org.spaceelephant.keepitapp.data.SessionRepository
import org.spaceelephant.keepitapp.data.offline.ConnectivityMonitor
import org.spaceelephant.keepitapp.data.offline.LocalStore
import org.spaceelephant.keepitapp.data.offline.Outbox
import org.spaceelephant.keepitapp.data.offline.SyncEngine
import org.spaceelephant.keepitapp.notifications.AppNotifications
import org.spaceelephant.keepitapp.notifications.ReminderScheduler
import org.spaceelephant.keepitapp.notifications.ServerNotificationsWatcher

/**
 * Process-wide wiring — a hand-rolled container instead of a DI framework (deliberate for v1: a
 * handful of singletons doesn't justify Hilt). Repositories are app-scoped so their StateFlows
 * survive configuration changes; screens reach them via [appContainer].
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

    private val localStore = LocalStore(context.applicationContext)
    private val outbox = Outbox(localStore)
    val connectivity = ConnectivityMonitor(context.applicationContext)
    val notesRepo = NotesRepository(apiClient, context.applicationContext, localStore, outbox, appScope)
    val syncEngine = SyncEngine(apiClient, outbox, connectivity, appScope, notesRepo)

    /** How many offline changes are still waiting to reach the server (notes screen strip). */
    val pendingChanges: StateFlow<Int> = outbox.pendingCount

    /** Local reminder alarms + native notifications, mirrored from the note cache. */
    val reminderScheduler = ReminderScheduler(context.applicationContext)

    /** Surfaces the server notification inbox (share invites …) as native notifications. */
    val notificationsWatcher = ServerNotificationsWatcher(context.applicationContext, apiClient, appScope)

    /** SignalR pushes mean the server moved on — one sync replays anything queued and refetches. */
    val realtime = RealtimeClient(apiClient, appScope) { resources ->
        if ("notes" in resources || "lists" in resources) syncEngine.kick()
        if ("notification" in resources) notificationsWatcher.kick()
    }

    init {
        AppNotifications.ensureChannels(context)
        notesRepo.syncEngine = syncEngine
        syncEngine.onUnauthorized = { session.sessionExpired() }
        connectivity.onOnline = { syncEngine.kick() }
        connectivity.start()
        realtime.onConnected = {
            syncEngine.kick()
            // Anything that landed in the inbox while the socket was down was missed too.
            notificationsWatcher.kick()
        }
        session.onLogout = {
            // Best-effort flush of queued changes while the session is still valid, then wipe.
            syncEngine.sync()
            notesRepo.clearLocal()
            reminderScheduler.clear()
            notificationsWatcher.clear()
        }

        // Keep the alarm snapshot in lockstep with the cache. drop(1) skips the StateFlow's initial
        // empty emission so an app start never wipes scheduled alarms before the disk cache loads.
        appScope.launch {
            notesRepo.allNotes.drop(1).collect { reminderScheduler.syncFrom(it) }
        }
        // Cold start (e.g. after a force-stop) may owe due reminders even before any cache change.
        appScope.launch { reminderScheduler.deliverDue() }
    }
}
