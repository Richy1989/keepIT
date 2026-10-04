package org.hyperstarit.keepitapp.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the About page's Android credits ([AboutContent.androidCredits]) to the libraries the app
 * actually ships: every `implementation` dependency in `build.gradle.kts` is either covered by a
 * credit or listed below as not credited, with why. A library added or removed without updating
 * this fails here, and so does a credit nothing uses any more: the page thanks the projects keepIT
 * is built on, and a list nobody checks is wrong within a month.
 *
 * Only what ships counts: test and debug-only dependencies aren't in the release, so they aren't
 * read. The web's and the server's credits have the same check, in the web's `about.test.ts`.
 */
class AboutCreditsTest {

    /** Each shipped dependency, by version-catalog alias, and the credit that covers it. */
    private val covers = mapOf(
        "androidx.compose.bom" to "Jetpack Compose and Material 3",
        "androidx.activity.compose" to "Jetpack Compose and Material 3",
        "androidx.compose.material3" to "Jetpack Compose and Material 3",
        "androidx.compose.ui" to "Jetpack Compose and Material 3",
        "androidx.compose.ui.graphics" to "Jetpack Compose and Material 3",
        "androidx.compose.ui.tooling.preview" to "Jetpack Compose and Material 3",
        "androidx.material.icons.extended" to "Material Icons",
        "androidx.core.ktx" to "AndroidX",
        "androidx.lifecycle.runtime.ktx" to "AndroidX",
        "androidx.navigation.compose" to "AndroidX",
        "androidx.glance.appwidget" to "AndroidX",
        "androidx.work.runtime.ktx" to "AndroidX",
        "retrofit" to "Retrofit and OkHttp",
        "retrofit.kotlinx.serialization" to "Retrofit and OkHttp",
        "okhttp" to "Retrofit and OkHttp",
        "kotlinx.serialization.json" to "Kotlin",
        "microsoft.signalr" to "SignalR Java client",
        "coil.compose" to "Coil",
        "commonmark" to "commonmark-java",
        "commonmark.ext.autolink" to "commonmark-java",
        "commonmark.ext.gfm.strikethrough" to "commonmark-java",
        "commonmark.ext.gfm.tables" to "commonmark-java",
        "commonmark.ext.task.list.items" to "commonmark-java",
    )

    /** Shipped dependencies with no credit of their own, and why. None today. */
    private val notCredited = emptyMap<String, String>()

    /** Gradle runs unit tests from the module directory, `app/app`, where the build script is. */
    private val shipped: List<String> by lazy {
        val script = File("build.gradle.kts")
        assertTrue("build.gradle.kts not found at ${script.absolutePath}", script.isFile)
        Regex("""^\s*(?:implementation|api|runtimeOnly)\((?:platform\()?libs\.([A-Za-z0-9.]+)\)""", RegexOption.MULTILINE)
            .findAll(script.readText())
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `every shipped library is credited, or said why not`() {
        for (alias in shipped) {
            assertTrue(
                "$alias is new: credit it in AboutContent.androidCredits, or list it as not credited and why",
                alias in covers || alias in notCredited,
            )
        }
    }

    @Test
    fun `nothing is listed that no longer ships`() {
        for (alias in covers.keys + notCredited.keys) {
            assertTrue("$alias no longer ships: drop it here, and its credit if nothing else needs it", alias in shipped)
        }
    }

    @Test
    fun `every credit is one the page has, and the page thanks nothing unused`() {
        val names = AboutContent.androidCredits.map { it.name }.toSet()
        for (credit in covers.values) assertTrue("$credit is not in AboutContent.androidCredits", credit in names)
        for (name in names) assertTrue("$name is credited, but no shipped library is that project", name in covers.values)
    }
}
