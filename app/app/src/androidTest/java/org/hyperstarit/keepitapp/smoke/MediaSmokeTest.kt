package org.hyperstarit.keepitapp.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds the reflectively-constructed media types off the real (minified) dex.
 *
 * A unit test cannot see this class of failure: [androidx.core.content.FileProvider] only ever comes
 * into being through a class name the framework reads from the manifest, so R8 sees no caller, keeps
 * the name and drops the constructor. Nothing fails at build time and nothing reaches our logs — the
 * camera button simply does nothing, in release only. That is the bug shape that shipped a dead
 * widget here twice.
 */
@RunWith(AndroidJUnit4::class)
class MediaSmokeTest {

    @Test
    fun fileProvider_canBeConstructedReflectively() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val type = Class.forName("androidx.core.content.FileProvider", true, context.classLoader)

        assertNotNull("FileProvider lost its no-arg constructor", type.getDeclaredConstructor().newInstance())
    }

    @Test
    fun fileProvider_isDeclaredForThisPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.fileprovider"

        val info = context.packageManager.resolveContentProvider(authority, 0)

        assertNotNull("No provider registered for $authority", info)
        assertTrue("FileProvider must not be exported", !info!!.exported)
    }
}
