package org.hyperstarit.keepitapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the message out of an API error body, as the web's `apiError.ts` does. The case that
 * mattered: a wrong password comes back as ASP.NET's ValidationProblemDetails, whose `title` is
 * only "One or more validation errors occurred." — the app showed that, rather than the message
 * the server put under `errors`.
 */
class ErrorBodyMessageTest {

    @Test
    fun `validation messages win over the generic title`() {
        val body = """
            {"type":"https://tools.ietf.org/html/rfc9110#section-15.5.1",
             "title":"One or more validation errors occurred.","status":400,
             "errors":{"Password":["That password isn't right."]}}
        """.trimIndent()

        assertEquals("That password isn't right.", errorBodyMessage(body))
    }

    @Test
    fun `several validation messages are joined`() {
        val body = """{"title":"One or more validation errors occurred.",
            "errors":{"PasswordTooShort":["Too short."],"PasswordRequiresDigit":["Needs a digit."]}}"""

        assertEquals("Too short. Needs a digit.", errorBodyMessage(body))
    }

    @Test
    fun `the error shape, then detail, then title`() {
        assertEquals("No user with that email.", errorBodyMessage("""{"error":"No user with that email."}"""))
        assertEquals("It broke.", errorBodyMessage("""{"title":"Server error","detail":"It broke."}"""))
        assertEquals("Server error", errorBodyMessage("""{"title":"Server error"}"""))
    }

    @Test
    fun `a body with no message, or no JSON, gives none`() {
        assertNull(errorBodyMessage("""{"status":429}"""))
        assertNull(errorBodyMessage("<html>Bad gateway</html>"))
        assertNull(errorBodyMessage(""))
    }
}
