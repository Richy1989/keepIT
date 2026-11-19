package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerVersionsTest {
    private val min = Triple(1, 3, 0)

    @Test
    fun `releases compare by number`() {
        assertEquals(false, ServerVersions.atLeast("1.2.0+fcd6d06", min))
        assertEquals(false, ServerVersions.atLeast("1.2.9", min))
        assertEquals(true, ServerVersions.atLeast("1.3.0+abc", min))
        assertEquals(true, ServerVersions.atLeast("1.10.0", min))
        assertEquals(true, ServerVersions.atLeast("2.0.0", min))
        assertEquals(false, ServerVersions.atLeast("0.9.2", min))
    }

    @Test
    fun `a beta counts as its release, and a development build as the newest`() {
        assertEquals(true, ServerVersions.atLeast("1.3.0-beta.1+abc", min))
        assertEquals(false, ServerVersions.atLeast("1.2.1-beta.3", min))
        assertEquals(true, ServerVersions.atLeast("0.0.0-dev+fcd6d06", min))
    }

    @Test
    fun `an unknown or unreadable version is unknown`() {
        assertEquals(null, ServerVersions.atLeast(null, min))
        assertEquals(null, ServerVersions.atLeast("", min))
        assertEquals(null, ServerVersions.atLeast("one.two", min))
        assertEquals(null, ServerVersions.atLeast("1.3", min))
    }
}
