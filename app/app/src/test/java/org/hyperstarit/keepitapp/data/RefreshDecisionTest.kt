package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app goes back to the server for a new access token. The case that mattered: a 401 for a
 * token the phone's clock still called fresh never refreshed — so a server whose signing key had
 * changed signed every phone out, and a phone whose account was deleted elsewhere was never told.
 */
class RefreshDecisionTest {

    @Test
    fun `ahead of a request, the clock decides`() {
        assertTrue(ApiClient.needsRefresh(rejectedToken = null, heldToken = "a", expiringSoon = true))
        assertFalse(ApiClient.needsRefresh(rejectedToken = null, heldToken = "a", expiringSoon = false))
    }

    @Test
    fun `a refused token is refreshed even while the clock calls it fresh`() {
        assertTrue(ApiClient.needsRefresh(rejectedToken = "a", heldToken = "a", expiringSoon = false))
    }

    @Test
    fun `a token refreshed by someone else meanwhile is used instead`() {
        assertFalse(ApiClient.needsRefresh(rejectedToken = "a", heldToken = "b", expiringSoon = false))
    }

    @Test
    fun `with no token left, a 401 refreshes`() {
        assertTrue(ApiClient.needsRefresh(rejectedToken = "a", heldToken = null, expiringSoon = true))
    }
}
