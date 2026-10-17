package org.hyperstarit.keepitapp.ui.notes

import org.hyperstarit.keepitapp.data.NoteCollaboratorDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The faces on a shared card, the same cases as the web's `collaborators.test.ts`: a long list
 * never grows the row past [MAX_FACES] faces and one "+N", and the words say who is on the note
 * and, for a collaborator, what they may do — the card's only remaining sign of view-only access.
 */
class CollaboratorFacesTest {

    private fun person(email: String, isOwner: Boolean = false) =
        NoteCollaboratorDto(userId = "id-$email", email = email, isOwner = isOwner)

    @Test
    fun `everyone shows while they fit`() {
        val people = listOf(person("a@x"), person("b@x"), person("c@x"))
        assertEquals(Faces(people, 0), facesOf(people))
    }

    @Test
    fun `the row stops at three faces and counts the rest`() {
        val people = listOf("a", "b", "c", "d", "e", "f", "g").map { person("$it@x") }
        val (shown, more) = facesOf(people)
        assertEquals(listOf("a@x", "b@x", "c@x"), shown.map { it.email })
        assertEquals(4, more)
    }

    @Test
    fun `a face without a picture shows the first letter of the email`() {
        assertEquals("A", initialOf("anna@example.com"))
        assertEquals("?", initialOf("  "))
    }

    @Test
    fun `the owner hears who has the note`() {
        val one = NoteDto(id = "n", isOwner = true, collaborators = listOf(person("ben@x")))
        assertEquals("Shared with ben@x", sharedLabel(one))

        val many = NoteDto(
            id = "n",
            isOwner = true,
            collaborators = listOf("ben", "cleo", "dan", "eve", "finn").map { person("$it@x") },
        )
        assertEquals("Shared with ben@x, cleo@x, dan@x and 2 others", sharedLabel(many))
    }

    @Test
    fun `a collaborator hears whose it is, who else is on it, and what they may do`() {
        val viewer = NoteDto(id = "n", isOwner = false, canEdit = false, collaborators = listOf(person("anna@x", true)))
        assertEquals("Shared by anna@x — view only", sharedLabel(viewer))

        val editor = NoteDto(
            id = "n",
            isOwner = false,
            canEdit = true,
            collaborators = listOf(person("anna@x", true), person("cleo@x"), person("dan@x")),
        )
        assertEquals("Shared by anna@x, also with cleo@x and dan@x — you can edit", sharedLabel(editor))
    }
}
