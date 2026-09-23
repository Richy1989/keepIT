package org.hyperstarit.keepitapp

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hyperstarit.keepitapp.data.ApiClient
import org.hyperstarit.keepitapp.data.AppMode
import org.hyperstarit.keepitapp.data.NotesRepository
import org.hyperstarit.keepitapp.data.RealtimeClient
import org.hyperstarit.keepitapp.data.SessionRepository
import org.hyperstarit.keepitapp.data.SessionState
import org.hyperstarit.keepitapp.data.offline.ConnectivityMonitor
import org.hyperstarit.keepitapp.data.offline.LocalStore
import org.hyperstarit.keepitapp.data.offline.MediaStaging
import org.hyperstarit.keepitapp.data.offline.Outbox
import org.hyperstarit.keepitapp.data.portability.PortabilityRepository
import org.hyperstarit.keepitapp.data.offline.SyncEngine
import org.hyperstarit.keepitapp.notifications.AppNotifications
import org.hyperstarit.keepitapp.notifications.ReminderScheduler
import org.hyperstarit.keepitapp.notifications.ServerNotificationsWatcher

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

    /** Server-backed or standalone; read by every path that would otherwise reach for the network. */
    val appMode = AppMode(context.applicationContext)

    val apiClient = ApiClient(context)
    val session = SessionRepository(apiClient, appMode)

    private val localStore = LocalStore(context.applicationContext)
    private val outbox = Outbox(localStore)
    private val mediaStaging = MediaStaging(context.applicationContext)
    val connectivity = ConnectivityMonitor(context.applicationContext)
    val notesRepo =
        NotesRepository(apiClient, context.applicationContext, localStore, outbox, appScope, mediaStaging)
    val syncEngine = SyncEngine(
        apiClient, outbox, connectivity, appScope, notesRepo, mediaStaging,
        isStandalone = { appMode.isStandalone },
    )

    /** Export and import of the archive format, server-backed or standalone (see the class doc). */
    val portability = PortabilityRepository(
        context.applicationContext,
        apiClient,
        notesRepo,
        outbox,
        appMode,
        appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty(),
    )

    /** Serializes [bootstrap]: two activity creations in a row must not both restore the session. */
    private val bootstrapLock = Mutex()

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
            // (A no-op in standalone mode, where signing out erases the device instead.)
            syncEngine.sync()
            // Still set here: the session only leaves standalone mode after this callback.
            val erasing = appMode.isStandalone
            notesRepo.clearLocal()
            if (erasing) notesRepo.clearWidget()
            reminderScheduler.clear()
            notificationsWatcher.clear()
        }
        session.onLeavingStandalone = { notesRepo.prepareStandaloneUpload() }

        // Keep the alarm snapshot in lockstep with the cache. drop(1) skips the StateFlow's initial
        // empty emission so an app start never wipes scheduled alarms before the disk cache loads.
        appScope.launch {
            notesRepo.allNotes.drop(1).collect { notes ->
                reminderScheduler.syncFrom(notes)
                // Standalone: no server marks reminders fired or advances them, so the device does
                // — after syncFrom, which has to post a due occurrence before it is moved past.
                if (appMode.isStandalone) notesRepo.settleReminders()
            }
        }
        // Cold start (e.g. after a force-stop) may owe due reminders even before any cache change.
        appScope.launch { reminderScheduler.deliverDue() }
    }

    /**
     * Brings the app up: the offline cache and outbox off disk, then the session from its refresh
     * cookie. Called when the UI composes, and safe to call again.
     *
     * Deliberately run on [appScope] rather than in the composition that asks for it. The session
     * is process-scoped state, and an activity recreation — a rotation, the system switching to
     * dark mode — used to cancel the restore half-way through and leave the app looking signed out.
     * An already-established session is left alone; an unresolved one is retried on the next open,
     * which is how a bootstrap that ran with no connectivity still comes good.
     */
    fun bootstrap() {
        appScope.launch {
            bootstrapLock.withLock {
                notesRepo.loadFromDisk()
                when (session.state.value) {
                    is SessionState.SignedIn, SessionState.Standalone -> Unit
                    else -> session.restore()
                }
            }
        }
    }

    /**
     * Standalone mode's stand-in for the refetch a server-backed app does on returning to the
     * foreground: reminders may have come due while it was away, and nothing else will move them
     * on. Delivery first, for the same reason as in the cache collector above.
     */
    fun onStandaloneResume() {
        appScope.launch {
            reminderScheduler.deliverDue()
            notesRepo.settleReminders()
        }
    }
}
