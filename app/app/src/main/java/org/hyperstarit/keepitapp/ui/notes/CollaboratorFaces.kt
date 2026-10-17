package org.hyperstarit.keepitapp.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hyperstarit.keepitapp.data.NoteCollaboratorDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.offline.CollaboratorPictures
import org.hyperstarit.keepitapp.ui.settings.ProfilePicture
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import java.io.File

/**
 * The faces on a shared note's card: who is shown, how many are left over, and what the row says
 * to TalkBack. The web's `features/notes/collaborators.ts` holds the same rules, so both cards
 * show the same people and say the same words.
 */

/** At most this many faces; anyone past them is counted in a "+N" circle instead. */
const val MAX_FACES = 3

/** The faces to draw, in order, and how many people the "+N" circle stands for (0: no circle). */
data class Faces(val shown: List<NoteCollaboratorDto>, val more: Int)

fun facesOf(people: List<NoteCollaboratorDto>): Faces {
    val shown = people.take(MAX_FACES)
    return Faces(shown, people.size - shown.size)
}

/** The letter a face without a picture shows: the first of the email, upper-cased. */
fun initialOf(email: String): String = email.trim().firstOrNull()?.uppercase() ?: "?"

/**
 * The row in words. The owner hears who has the note ("Shared with ben@x and cleo@y"); a
 * collaborator hears whose it is, who else is on it, and what they may do — which the card no
 * longer says any other way.
 */
fun sharedLabel(note: NoteDto): String {
    val people = note.collaborators
    if (note.isOwner) return "Shared with ${namesOf(people.map { it.email })}"

    val owner = people.firstOrNull { it.isOwner }
    val others = people.filterNot { it.isOwner }.map { it.email }
    val access = if (note.canEdit) "you can edit" else "view only"
    val by = if (owner != null) "Shared by ${owner.email}" else "Shared with you"
    return if (others.isNotEmpty()) "$by, also with ${namesOf(others)} — $access" else "$by — $access"
}

/** "a", "a and b", "a, b and c", "a, b, c and 2 others": three names at most. */
private fun namesOf(names: List<String>): String = when {
    names.size <= 1 -> names.firstOrNull().orEmpty()
    names.size <= MAX_FACES -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
    else -> {
        val rest = names.size - MAX_FACES
        "${names.take(MAX_FACES).joinToString(", ")} and $rest ${if (rest == 1) "other" else "others"}"
    }
}

/** A face's picture or initial, and the [RING] around it in the colour behind the row. */
private val FACE = 22.dp
private val RING = 2.dp

/** How far each face slides under the next: about a third of it. */
private val OVERLAP = 8.dp

/**
 * The people on a shared note, in its card's footer: a face each, overlapping, owner first — up to
 * [MAX_FACES] and then a "+N" circle, so a long list never widens the row. Later faces lie over
 * earlier ones, as on the web. Each face is ringed in [ring], the colour behind the row, so where
 * two overlap the one in front reads as cut out of the one behind.
 *
 * Pictures come from [pictures], from disk when they are there: offline a face shows whatever was
 * fetched before, else the person's initial. TalkBack reads the row as one item, [sharedLabel].
 */
@Composable
fun CollaboratorFaces(
    note: NoteDto,
    pictures: CollaboratorPictures,
    ring: Color,
    modifier: Modifier = Modifier,
) {
    val (shown, more) = facesOf(note.collaborators)
    if (shown.isEmpty()) return
    val label = sharedLabel(note)

    Layout(
        content = {
            shown.forEach { person ->
                Face(ring) {
                    Initial(initialOf(person.email))
                    ProfilePicture(rememberPicture(pictures, person))
                }
            }
            if (more > 0) Face(ring) { Initial("+$more") }
        },
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val step = (FACE + RING * 2 - OVERLAP).roundToPx()
        val width = step * (placeables.size - 1) + placeables.last().width
        layout(width, placeables.maxOf { it.height }) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(index * step, 0) }
        }
    }
}

/** [person]'s picture on disk, fetched first if it has to be; null while it loads, or without one. */
@Composable
private fun rememberPicture(pictures: CollaboratorPictures, person: NoteCollaboratorDto): File? {
    val file by produceState<File?>(null, person.userId, person.profileImageVersion) {
        value = person.profileImageVersion?.let { pictures.file(person.userId, it) }
    }
    return file
}

/** One circle of the row: the ring, and inside it the face on the avatar fill. */
@Composable
private fun Face(ring: Color, content: @Composable BoxScope.() -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(FACE + RING * 2).background(ring, CircleShape),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(FACE).clip(CircleShape).background(KeepItColors.Elevated),
            content = content,
        )
    }
}

@Composable
private fun Initial(text: String) {
    Text(text = text, color = KeepItColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
}
