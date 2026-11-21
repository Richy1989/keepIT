package org.hyperstarit.keepitapp.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.accentButtonColors

/**
 * Files notes into lists or takes them out of them — the one list picker, shared by the editor
 * (one note) and the grid's selection (many). [membership] says where the notes stand in a list:
 * true when all are in it, false when none is, null when only some are (a dash, which only a
 * selection can produce). A tap applies at once: a checked box takes them out, anything else puts
 * them in.
 *
 * The field at the top filters the lists, so a long collection stays usable, and names a new one:
 * "New list" becomes "Create “…”" while it holds a name no list has, and [onNewList] gets that name
 * to prefill the [ListDialog] with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListsSheet(
    lists: List<ListDto>,
    membership: (listId: String) -> Boolean?,
    onToggle: (listId: String, member: Boolean) -> Unit,
    onNewList: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val name = query.trim()
    val shown = if (name.isEmpty()) lists else lists.filter { it.name.contains(name, ignoreCase = true) }
    val exists = lists.any { it.name.equals(name, ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KeepItColors.Surface) {
        Column(modifier = Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            SheetTitle("Lists")
            if (lists.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Find or create a list", color = KeepItColors.TextFaint) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = KeepItColors.TextFaint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            shown.forEach { list ->
                val state = when (membership(list.id)) {
                    true -> ToggleableState.On
                    false -> ToggleableState.Off
                    null -> ToggleableState.Indeterminate
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .triStateToggleable(
                            state = state,
                            role = Role.Checkbox,
                            onClick = { onToggle(list.id, state != ToggleableState.On) },
                        )
                        .padding(horizontal = 24.dp),
                ) {
                    TriStateCheckbox(
                        state = state,
                        onClick = null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = KeepItColors.Accent,
                            checkmarkColor = Color.Black,
                            // BorderStrong is a divider's colour: 1.7:1 at best on the sheet.
                            uncheckedColor = KeepItColors.BorderControl,
                        ),
                    )
                    ListGlyph(list.icon, modifier = Modifier.padding(start = 20.dp))
                    Text(
                        text = list.name,
                        color = KeepItColors.Text,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
            }
            if (!exists) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable(onClick = { onNewList(name) })
                        .padding(horizontal = 24.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = KeepItColors.TextMuted, modifier = Modifier.size(24.dp))
                    Text(
                        text = if (name.isEmpty()) "New list" else "Create “$name”",
                        color = KeepItColors.TextMuted,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Name and icon prompt shared by create and edit. Confirm is disabled while the name is blank; the
 * icon is optional, and the name field leads with the one chosen so far. The emoji grid is the web
 * picker's ([ListIcons]); the dialog scrolls when a short or landscape screen can't fit it.
 */
@Composable
internal fun ListDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialIcon: String?,
    onConfirm: (name: String, icon: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KeepItColors.Surface,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    leadingIcon = { ListGlyph(icon) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                ListIconGrid(selected = icon, onPick = { icon = it })
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), colors = accentButtonColors(), onClick = { onConfirm(name.trim(), icon) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KeepItColors.TextMuted)
            }
        },
    )
}

/** [ListDialog] as "New list", prefilled with [initialName] (what was typed into [ListsSheet]). */
@Composable
internal fun NewListDialog(
    initialName: String = "",
    onCreate: (name: String, icon: String?) -> Unit,
    onDismiss: () -> Unit,
) = ListDialog(
    title = "New list",
    confirmLabel = "Create",
    initialName = initialName,
    initialIcon = null,
    onConfirm = onCreate,
    onDismiss = onDismiss,
)
