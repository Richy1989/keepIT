package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a refused sign-in is read. The case that matters: the right password for an account with
 * two-factor on comes back as a 401 too, and only its body says to ask for the code — read wrong,
 * the sign-in screen would tell someone with the right password that it was wrong.
 */
class LoginRefusalTest {

    @Test
    fun `the right password on a two-factor account asks for the code`() {
        val refusal = loginRefusal("""{"error":"Enter the code from your authenticator app.","twoFactorRequired":true}""")

        assertTrue(refusal.twoFactorRequired)
        assertEquals("Enter the code from your authenticator app.", refusal.message)
    }

    @Test
    fun `wrong credentials are a plain refusal`() {
        val refusal = loginRefusal("""{"error":"Invalid email or password.","twoFactorRequired":false}""")

        assertFalse(refusal.twoFactorRequired)
        assertEquals("Invalid email or password.", refusal.message)
    }

    @Test
    fun `an older server's body, or none, is a plain refusal of the credentials`() {
        assertFalse(loginRefusal("""{"error":"Invalid email or password."}""").twoFactorRequired)
        assertEquals("Invalid email or password.", loginRefusal(null).message)
        assertEquals("Invalid email or password.", loginRefusal("<html>proxy error</html>").message)
    }
}
