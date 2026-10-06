package org.hyperstarit.keepitapp.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * The emoji a list can wear, in the order the picker shows them: rows of eight, loosely by theme
 * (home, work, money and health, travel, leisure, the rest).
 *
 * A hand copy of the web's `web/src/features/lists/listIcons.json`, which is the canonical side —
 * ListIconsParityTest holds the two to the same set and order. The server holds icons only to "one
 * symbol" ([org.hyperstarit.keepitapp.data.NoteLimits.listIconOrNull] mirrors that rule), not to
 * this set, so a list keeps an icon that isn't here and the picker shows it as the current one.
 */
object ListIcons {
    val all: List<String> = listOf(
        "🛒", "🏠", "🍳", "🍎", "🧺", "🧹", "🛠️", "🌱",
        "💼", "📚", "🎓", "💡", "📝", "📌", "📅", "📎",
        "💰", "💳", "💊", "🩺", "🏃", "🏋️", "🧘", "❤️",
        "✈️", "🧳", "🗺️", "🏖️", "🚗", "🚲", "⛺", "🎒",
        "🎵", "🎬", "🎮", "📷", "🎨", "📖", "🎁", "🎉",
        "⭐", "🔥", "✅", "🔒", "☕", "🍕", "👶", "🐶",
    )

    /** Emoji per picker row; the web's grid is eight columns too. */
    const val COLUMNS = 8
}

/**
 * A list's icon where an icon goes: its emoji, or the generic label icon when it has none. Both fill
 * the same [size] box, so a column of lists lines up whichever each one shows.
 */
@Composable
internal fun ListGlyph(icon: String?, modifier: Modifier = Modifier, size: Int = 24) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size.dp)) {
        if (icon == null) {
            Icon(
                Icons.AutoMirrored.Outlined.Label,
                contentDescription = null,
                tint = KeepItColors.TextFaint,
                modifier = Modifier.size((size - 4).dp),
            )
        } else {
            // Colour emoji draw wider than their font size; three quarters of the box keeps them in it.
            Text(icon, fontSize = (size * 0.75f).sp)
        }
    }
}

/**
 * The emoji grid of the list dialog, with "No icon" while one is chosen. [selected] is the current
 * icon (null for none); a tap calls [onPick] with the emoji, or null to remove it.
 */
@Composable
internal fun ListIconGrid(selected: String?, onPick: (String?) -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Text("Icon", color = KeepItColors.TextFaint, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (selected != null) {
                TextButton(onClick = { onPick(null) }) {
                    Text("No icon", color = KeepItColors.TextMuted)
                }
            }
        }
        ListIcons.all.chunked(ListIcons.COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { icon ->
                    val isSelected = icon == selected
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .background(KeepItColors.Accent.copy(alpha = 0.15f), ListCellShape)
                                        .border(1.dp, KeepItColors.AccentInk, ListCellShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clip(ListCellShape)
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(icon) }),
                    ) {
                        Text(icon, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

private val ListCellShape = RoundedCornerShape(8.dp)
