package org.hyperstarit.keepitapp.smoke

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hyperstarit.keepitapp.data.NotesRepository
import org.hyperstarit.keepitapp.data.WidgetNote
import org.hyperstarit.keepitapp.widget.KeepItWidget
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Composes the home-screen widget for real, on the minified build, and inflates the RemoteViews it
 * produces.
 *
 * This covers the half of the widget that [org.hyperstarit.keepitapp.widget.WidgetSnapshotTest]
 * can't: Glance encodes its layout tree through a shaded protobuf and resolves generated layout
 * resources by id, so a composition that works fine in debug can come back empty — or throw —
 * once R8 and the resource shrinker have been through it. A widget that fails here is a widget
 * that shows a spinner forever on someone's home screen, with nothing in logcat to explain it.
 *
 * Note [compose] renders the composition directly rather than going through Glance's
 * WorkManager session, so it does not exercise the worker path — [ReleaseBuildSmokeTest] covers
 * that side.
 */
@RunWith(AndroidJUnit4::class)
class WidgetCompositionSmokeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("keepit_widget", Context.MODE_PRIVATE)
    private var saved: String? = null

    @Before fun captureSnapshot() { saved = prefs.getString("notes_json", null) }

    @After fun restoreSnapshot() {
        prefs.edit().apply { if (saved == null) remove("notes_json") else putString("notes_json", saved) }.apply()
    }

    private fun seed(vararg notes: WidgetNote) {
        prefs.edit().putString("notes_json", NotesRepository.encodeWidgetSnapshot(notes.toList())).apply()
    }

    // No explicit size: Dp is a Kotlin value class, and its `constructor-impl` static is shrunk
    // out of the app APK when app code never boxes one - linking the test against it fails with
    // NoSuchMethodError. Glance's default sizing is all this needs anyway.
    @OptIn(ExperimentalGlanceApi::class)
    private fun composeWidget(): RemoteViews = runBlocking { KeepItWidget().compose(context) }

    /** Inflates on the main thread, as a launcher would, and returns every view in the tree. */
    private fun inflate(views: RemoteViews): List<View> {
        lateinit var root: View
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = views.apply(context, FrameLayout(context))
        }
        return buildList { fun walk(v: View) { add(v); if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) } }; walk(root) }
    }

    private fun texts(views: RemoteViews) = inflate(views)
        .filterIsInstance<TextView>()
        .map { it.text.toString() }

    @Test
    fun the_widget_composes_and_inflates_with_notes() {
        seed(
            WidgetNote("n1", "Release checklist", "", "sky", listOf("☐ Smoke test auth flow")),
            WidgetNote("n2", "Q3 roadmap", "Ship sharing, then image notes", "amber"),
        )

        val views = composeWidget()
        assertNotNull("Glance produced no RemoteViews", views)
        val tree = inflate(views)

        // What the note rows themselves say cannot be asserted here: Glance backs LazyColumn with a
        // RemoteViews *collection*, and only a real AppWidget host binds the adapter that fills it.
        // apply() inflates the chrome and an empty list container. The row contents are covered on
        // the data side by WidgetSnapshotTest; what this proves is that the composition ran, the
        // protobuf layout tree encoded, and every generated layout resource it names survived the
        // resource shrinker - the failure mode that leaves a widget spinning forever.
        assertTrue("header missing from ${texts(views)}", texts(views).any { it == "keepIT" })
        assertTrue("suspiciously empty view tree: ${tree.size} views", tree.size > 3)
    }

    @Test
    fun a_note_with_an_unknown_colour_still_composes() {
        // The colour key comes off the wire, so noteSwatch() must fall back rather than throw
        // mid-composition - which would take the whole widget down, not just one row.
        seed(WidgetNote("n1", "Mystery", "", "chartreuse"))

        assertTrue(texts(composeWidget()).any { it == "keepIT" })
    }

    @Test
    fun the_empty_state_composes_when_there_is_no_snapshot() {
        prefs.edit().remove("notes_json").apply()

        assertTrue(texts(composeWidget()).any { it.contains("No notes yet") })
    }

    @Test
    fun a_corrupt_snapshot_degrades_to_the_empty_state_instead_of_failing_to_compose() {
        prefs.edit().putString("notes_json", "{not json").apply()

        assertTrue(texts(composeWidget()).any { it.contains("No notes yet") })
    }
}
