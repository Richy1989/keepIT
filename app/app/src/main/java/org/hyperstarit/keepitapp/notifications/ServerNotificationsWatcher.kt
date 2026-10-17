package org.hyperstarit.keepitapp.notifications

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hyperstarit.keepitapp.data.ApiClient
import org.hyperstarit.keepitapp.data.NotificationTypes
import org.hyperstarit.keepitapp.data.UserNotificationDto
import org.hyperstarit.keepitapp.data.ensureUtc
import java.time.Instant

/**
 * Holds the server-side notification inbox (`GET api/notifications`) — share invites, system
 * messages, and the server's reminder rows — and surfaces new entries as **native Android
 * notifications**. Checked whenever the realtime hub pushes the `notification` resource and on
 * every (re)connect.
 *
 * The tray alone loses whatever is swiped away, or never shown with notifications off, so the
 * inbox it fetched is kept as [inbox] too: the in-app inbox lists it, and the notes screen's bell
 * shows its [unread] count, the phone twin of the web's bell badge.
 *
 * Already-posted ids are remembered locally so an inbox item alerts once per device; the first
 * check after install/sign-in seeds that set silently, so a pre-existing backlog doesn't blast the
 * tray (it still counts on the bell). Reminder-type items reuse the per-note tag, folding into a
 * locally-fired copy instead of duplicating it. Checking never marks the inbox read — only opening
 * the in-app inbox does ([markAllRead]), as opening the web's bell does, and dismissing a native
 * notification is a device-local act.
 */
class ServerNotificationsWatcher(
    private val context: Context,
    private val client: ApiClient,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val checkMutex = Mutex()

    private val _inbox = MutableStateFlow<List<UserNotificationDto>?>(null)

    /** The inbox as last fetched, newest first; null until the first fetch since start or sign-in. */
    val inbox: StateFlow<List<UserNotificationDto>?> = _inbox.asStateFlow()

    /** How many inbox entries are unread (`isActive`) — the count on the bell. */
    val unread: StateFlow<Int> = _inbox
        .map { items -> items.orEmpty().count { it.isActive } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Fire-and-forget check; failures are silent (the next push/connect tries again). */
    fun kick() {
        scope.launch { runCatching { check() } }
    }

    /** Fetches the inbox now, for the in-app inbox: unlike [kick], a failure reaches the caller. */
    suspend fun refresh() = check()

    /**
     * Marks the whole inbox read, which zeroes [unread]. A no-op while nothing is unread, so the
     * inbox can call it whenever the count rises while it is open.
     */
    suspend fun markAllRead() {
        checkMutex.withLock {
            if (_inbox.value.orEmpty().none { it.isActive }) return
            client.api.markNotificationsRead()
            _inbox.update { items -> items?.map { if (it.isActive) it.copy(isActive = false) else it } }
        }
    }

    /** Sign-out: forget what was posted so the next account starts from a clean seed. */
    fun clear() {
        prefs.edit().clear().apply()
        _inbox.value = null
    }

    private suspend fun check() {
        checkMutex.withLock {
            val inbox = client.api.notifications()
            val posted = (prefs.getStringSet(KEY_POSTED, emptySet()) ?: emptySet()).toMutableSet()
            val seeded = prefs.getBoolean(KEY_SEEDED, false)

            for (item in inbox) {
                val id = item.id ?: continue
                if (id in posted) continue
                posted.add(id)
                // Seed pass and already-read items register silently — only fresh, active
                // notifications reach the tray.
                if (!seeded || !item.isActive) continue
                if (item.type == NotificationTypes.REMINDER && item.reminderNoteId != null) {
                    AppNotifications.postReminder(
                        context,
                        noteId = item.reminderNoteId,
                        title = item.reminderNoteTitle,
                        whenMs = runCatching {
                            Instant.parse(ensureUtc(item.createdAtUtc)).toEpochMilli()
                        }.getOrDefault(System.currentTimeMillis()),
                        alertOnce = true,
                    )
                } else {
                    AppNotifications.postGeneral(context, id, item.notificationText)
                }
            }

            // Ids of deleted (dismissed/answered) inbox items can be dropped — they can't reappear.
            val live = inbox.mapNotNull { it.id }.toSet()
            prefs.edit()
                .putStringSet(KEY_POSTED, posted.intersect(live))
                .putBoolean(KEY_SEEDED, true)
                .apply()
            _inbox.value = inbox
        }
    }

    private companion object {
        const val PREFS_NAME = "keepit_inbox"
        const val KEY_POSTED = "posted_ids"
        const val KEY_SEEDED = "seeded"
    }
}
