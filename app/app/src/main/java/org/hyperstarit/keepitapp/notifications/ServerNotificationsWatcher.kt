package org.hyperstarit.keepitapp.notifications

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hyperstarit.keepitapp.data.ApiClient
import org.hyperstarit.keepitapp.data.NotificationTypes
import org.hyperstarit.keepitapp.data.ensureUtc
import java.time.Instant

/**
 * Surfaces the server-side notification inbox (`GET api/notifications`) as **native Android
 * notifications** — share invites, system messages, and the server's reminder rows. There is no
 * in-app bell on Android; the system tray is the one notification surface. Checked whenever the
 * realtime hub pushes the `notification` resource and on every (re)connect.
 *
 * Already-posted ids are remembered locally so an inbox item alerts once per device; the first
 * check after install/sign-in seeds that set silently, so a pre-existing backlog doesn't blast the
 * tray. Reminder-type items reuse the per-note tag, folding into a locally-fired copy instead of
 * duplicating it. The inbox is deliberately never marked read from here — the web bell stays
 * accurate, and dismissing a native notification is a device-local act.
 */
class ServerNotificationsWatcher(
    private val context: Context,
    private val client: ApiClient,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val checkMutex = Mutex()

    /** Fire-and-forget check; failures are silent (the next push/connect tries again). */
    fun kick() {
        scope.launch { runCatching { check() } }
    }

    /** Sign-out: forget what was posted so the next account starts from a clean seed. */
    fun clear() {
        prefs.edit().clear().apply()
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
        }
    }

    private companion object {
        const val PREFS_NAME = "keepit_inbox"
        const val KEY_POSTED = "posted_ids"
        const val KEY_SEEDED = "seeded"
    }
}
