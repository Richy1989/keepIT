package org.hyperstarit.keepitapp.offline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hyperstarit.keepitapp.data.offline.nextOccurrenceAfter
import org.hyperstarit.keepitapp.data.offline.reminderZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Holds [nextOccurrenceAfter] to the server's own cases, `ReminderOccurrences.json` in the API's
 * test project, which the server's `ReminderSchedule` is tested against as well.
 *
 * The phone moves reminders on by itself (between syncs, with the app closed, and in standalone
 * mode always), so it has its own copy of the server's rules. If the copy drifts, the phone and the
 * server put the same reminder up an hour apart: twice, and once at the wrong time. Reading the
 * server's cases rather than writing our own is what makes this a test of agreement.
 */
class ReminderOccurrenceTest {

    /** Gradle runs unit tests from the module directory, `app/app`; the API is a sibling. */
    private val cases by lazy {
        val file = File("../../keepIT/keepITCore.Tests/ReminderOccurrences.json")
        assertTrue("ReminderOccurrences.json not found at ${file.absolutePath}", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    }

    @Test
    fun `the next occurrence matches the server's in every case`() {
        assertTrue("no cases read", cases.size >= 10)
        for (case in cases) {
            fun field(name: String) = case.getValue(name).jsonPrimitive.content
            fun ms(name: String) = Instant.parse(field(name)).toEpochMilli()

            val next = nextOccurrenceAfter(
                ms("firstAtUtc"),
                ZoneId.of(field("zone")),
                field("recurrence"),
                ms("afterUtc"),
            )

            assertEquals(field("name"), field("nextUtc"), Instant.ofEpochMilli(next).toString())
        }
    }

    @Test
    fun `a zone the server didn't name, or the phone can't find, counts in UTC`() {
        // A server from before reminders carried a zone counted repeats in UTC.
        assertEquals(ZoneOffset.UTC, reminderZone(null))
        assertEquals(ZoneOffset.UTC, reminderZone("Mars/Olympus_Mons"))
        assertEquals(ZoneId.of("Europe/Vienna"), reminderZone("Europe/Vienna"))
    }
}
