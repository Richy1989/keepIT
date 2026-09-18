package org.hyperstarit.keepitapp.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.hyperstarit.keepitapp.appContainer
import java.util.concurrent.TimeUnit

/**
 * Keeps the home-screen widget current while the app is closed.
 *
 * Nothing else does: the widget's descriptor sets `updatePeriodMillis="0"` because the app pushes
 * its own updates, and realtime pushes only arrive while the app process is alive. So with the app
 * closed the widget froze on whatever it last saw, and the only way to move it was the refresh
 * button — the complaint this exists to answer.
 *
 * The body is deliberately identical to what the refresh button does, for the same reasons
 * ([RefreshAction] carries the full explanation): load the cache off disk because no UI has run in
 * this process, sync, then render from here rather than leaving it to the repository's debounced
 * updater, which the process may not live long enough to reach.
 */
class WidgetSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        container.notesRepo.loadFromDisk()
        // A failed sync is an ordinary outcome out here (no session, server unreachable). It is not
        // worth a Result.retry(): that would add a backoff run on top of a schedule that already
        // comes back every REPEAT_MINUTES, and a signed-out install would retry forever.
        runCatching { container.syncEngine.sync() }
        container.notesRepo.renderWidgetNow()
        return Result.success()
    }

    companion object {
        /**
         * WorkManager's floor is 15 minutes; 30 keeps the widget usefully fresh without waking the
         * radio twice an hour for a notes app.
         */
        private const val REPEAT_MINUTES = 30L

        /** Unique, so re-scheduling from several entry points can never stack up duplicate work. */
        private const val WORK_NAME = "keepit-widget-sync"

        /**
         * Schedules the refresh if it isn't already scheduled.
         *
         * [ExistingPeriodicWorkPolicy.KEEP] makes this idempotent and, more to the point, stops
         * every call resetting the interval — which would leave a widget that is re-scheduled often
         * enough never actually reaching its first run.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(REPEAT_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    // Nothing to fetch without a network, and the run would only burn a wakeup.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Stops the refresh once the last widget is gone — no widget, no reason to wake up. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
