package org.hyperstarit.keepitapp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the app works against a keepIT server or **standalone**, keeping everything on this
 * device with no server at all.
 *
 * Standalone mode needs no new storage — it is the offline-first design with the network taken
 * away. Notes, lists, reminders and images live in the same local cache and outbox a server user's
 * do; the only difference is that the sync engine never drains the outbox. That is also what makes
 * connecting a server later possible: the queue already holds every change in the order it was made,
 * so replaying it uploads the lot into an account.
 *
 * Persisted on its own, not derived from the session, because processes with no UI — the widget's
 * refresh, its background worker, the alarm receiver — must know the mode before any session is
 * restored. Written with `commit()` so a mode switch is on disk before the app acts on it.
 */
class AppMode(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _standalone = MutableStateFlow(prefs.getBoolean(KEY_STANDALONE, false))

    /** True while the app runs without a server; observed by screens that hide server features. */
    val standalone: StateFlow<Boolean> = _standalone

    /** Snapshot of [standalone], for non-UI callers. */
    val isStandalone: Boolean get() = _standalone.value

    fun setStandalone(standalone: Boolean) {
        prefs.edit().putBoolean(KEY_STANDALONE, standalone).commit()
        _standalone.value = standalone
    }

    private companion object {
        const val PREFS_NAME = "keepit_mode"
        const val KEY_STANDALONE = "standalone"
    }
}
