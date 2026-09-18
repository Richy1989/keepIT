package org.hyperstarit.keepitapp.smoke

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkerParameters
import org.hyperstarit.keepitapp.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "does the shipped build actually work" gate. Runs against the **minified** variant
 * (`testBuildType = "minified"`), which carries release's R8 configuration — that is the whole
 * point. Every assertion here passes trivially on a debug build; the failures they exist to catch
 * only come into existence after R8 has run.
 *
 * Each test stands in for a regression this app has actually shipped.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseBuildSmokeTest {

    /**
     * Regression: R8 stripped the constructor of Room's generated `WorkDatabase_Impl`. WorkManager
     * registers itself as an androidx.startup initializer, which runs at process creation — so the
     * app died before drawing anything, on every cold start, in release only.
     *
     * Reaching RESUMED means Application.onCreate, every startup initializer and the first
     * composition all survived minification.
     */
    @Test
    fun the_app_launches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /**
     * Regression: R8 stripped the no-arg constructor of `androidx.work.OverwritingInputMerger`.
     * WorkManager's `WorkerWrapper` builds a request's input merger by class name *before* running
     * any worker, so the reflective `newInstance()` threw and every job failed before starting.
     * Nothing crashed and nothing logged — Glance's widget session simply never ran, and the widget
     * sat on its loading layout forever.
     *
     * This does exactly what WorkerWrapper does, in the app process, against the real minified dex.
     * Deliberately not an end-to-end `enqueue()`: that would hang on JobScheduler timing and
     * device-idle state, which is flake this cannot afford in CI. `verifyReleaseKeepRules` makes
     * the same assertion against R8's build-time report; this one proves it of the shipped dex.
     */
    @Test
    fun work_managers_input_mergers_can_still_be_constructed() {
        listOf(
            "androidx.work.OverwritingInputMerger",
            "androidx.work.ArrayCreatingInputMerger",
        ).forEach { fqcn ->
            val attempt = runCatching { Class.forName(fqcn).getDeclaredConstructor().newInstance() }

            assertTrue(
                "$fqcn could not be constructed reflectively - R8 stripped its constructor, so " +
                    "WorkerWrapper fails every job in this build. Fix the -keep rule in " +
                    "proguard-rules.pro. Cause: ${attempt.exceptionOrNull()}",
                attempt.isSuccess,
            )
            assertNotNull(attempt.getOrNull())
        }
    }

    /**
     * The Glance callback behind the widget's refresh button. Its PendingIntent stores the class
     * *name* and resolves it with `Class.forName` plus a no-arg constructor at tap time — and that
     * PendingIntent outlives app updates, so the name has to survive R8 as well as the constructor.
     */
    @Test
    fun the_widgets_refresh_callback_can_still_be_resolved_by_name() {
        val attempt = runCatching {
            Class.forName("org.hyperstarit.keepitapp.widget.RefreshAction")
                .getDeclaredConstructor()
                .newInstance()
        }

        assertTrue("RefreshAction is unreachable by name: ${attempt.exceptionOrNull()}", attempt.isSuccess)
    }

    /**
     * The periodic worker that keeps the widget current while the app is closed. WorkManager stores
     * the class *name* in its own database and instantiates it from there, so a stripped constructor
     * would stop the background refresh with nothing logged — the same shape as the input-merger bug
     * above, just further from the eye.
     *
     * The constructor is only looked up, not invoked: building real [WorkerParameters] needs
     * WorkManager's internals, and it is the lookup that R8 breaks.
     */
    @Test
    fun the_widgets_background_sync_worker_keeps_its_constructor() {
        val attempt = runCatching {
            Class.forName("org.hyperstarit.keepitapp.widget.WidgetSyncWorker")
                .getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
        }

        assertTrue(
            "WidgetSyncWorker lost its (Context, WorkerParameters) constructor, so WorkManager " +
                "cannot build it and the widget stops refreshing in the background. " +
                "Cause: ${attempt.exceptionOrNull()}",
            attempt.isSuccess,
        )
    }
}

/**
 * Guards the harness itself. If the test APK were ever built against the wrong variant, every test
 * above would still pass — while testing nothing, because an unminified build cannot reproduce any
 * of the failures they describe.
 */
@RunWith(AndroidJUnit4::class)
class VariantSanityTest {

    @Test
    fun tests_run_against_the_minified_variant() {
        val pkg = ApplicationProvider.getApplicationContext<Context>().packageName

        assertTrue(
            "instrumented tests must target the minified variant, but ran against '$pkg' - " +
                "check testBuildType in app/build.gradle.kts",
            pkg.endsWith(".minified"),
        )
    }
}
