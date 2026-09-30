package org.hyperstarit.keepitapp.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hyperstarit.keepitapp.ui.markdown.stripMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parses a note body off the real (minified) dex.
 *
 * Every card, the viewer, the editor's live styling and the widget preview go through
 * commonmark-java, so a release build that shrank part of it away would break every note at once —
 * and nowhere but release. The part most at risk is not code: commonmark decodes HTML entities from
 * a text file inside its jar, read by name when that class first loads, which neither R8's
 * reachability analysis nor a JVM unit test (running the unshrunk jar) would ever question.
 *
 * Asserted as plain text, off a String-to-String entry point, so the test touches no Compose types
 * and none of the app's data classes (see `proguard-rules-minified.pro`).
 */
@RunWith(AndroidJUnit4::class)
class MarkdownSmokeTest {

    @Test
    fun a_note_body_parses_with_its_entities_and_extensions() {
        assertEquals(
            // An entity (the jar's resource file), emphasis, a bare URL (autolink), a task list
            // item (task-list extension) and strikethrough (GFM extension).
            "© Bold https://link.example\n☐ item gone",
            stripMarkdown("&copy; **Bold** https://link.example\n- [ ] item ~~gone~~"),
        )
    }
}
