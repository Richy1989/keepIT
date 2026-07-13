package org.spaceelephant.keepitapp.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.spaceelephant.keepitapp.AppContainer
import org.spaceelephant.keepitapp.ui.markdown.MarkdownAction
import org.spaceelephant.keepitapp.ui.markdown.MarkdownText
import org.spaceelephant.keepitapp.ui.markdown.applyMarkdown
import org.spaceelephant.keepitapp.data.ChecklistItemDto
import org.spaceelephant.keepitapp.data.CreateNoteDto
import org.spaceelephant.keepitapp.data.NoteDto
import org.spaceelephant.keepitapp.data.NoteStateDto
import org.spaceelephant.keepitapp.data.NoteTypes
import org.spaceelephant.keepitapp.data.UpdateNoteDto
import org.spaceelephant.keepitapp.ui.theme.KeepItColors
import org.spaceelephant.keepitapp.ui.theme.NotePalette
import org.spaceelephant.keepitapp.ui.theme.noteSwatch

/**
 * A checklist row being edited. `id` is kept so the server reconciles instead of recreating;
 * `localId` is a stable client key (for focus targeting) since new rows have no server id yet.
 */
private class EditableItem(id: String?, text: String, isChecked: Boolean) {
    val id = id
    val localId = nextLocalId++
    val focusRequester = FocusRequester()
    var text by mutableStateOf(text)
    var isChecked by mutableStateOf(isChecked)

    companion object {
        private var nextLocalId = 0L
    }
}

/**
 * Full-note editor, the phone twin of the web NoteEditorModal: title, body or checklist, and a
 * bottom toolbar (color + text/checklist toggle) matching the web editor's footer. Saves on leaving
 * (back button or the top-left arrow) rather than with an explicit save; viewers see content
 * read-only but may still file the note into their own lists (list membership is per-user).
 *
 * With a null [noteId] it's the composer — the widget's "+" lands here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(container: AppContainer, noteId: String?, onDone: () -> Unit) {
    val repo = container.notesRepo
    val scope = rememberCoroutineScope()
    val lists by repo.lists.collectAsState()

    // Existing note: prefer the grid cache, fall back to a direct fetch (widget deep link).
    var loaded by remember { mutableStateOf(noteId == null) }
    var note by remember { mutableStateOf<NoteDto?>(null) }

    var type by remember { mutableStateOf(NoteTypes.TEXT) }
    var title by remember { mutableStateOf("") }
    // TextFieldValue (not String) so the Markdown toolbar can rewrite the current selection.
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var color by remember { mutableStateOf<String?>(null) }
    val items = remember { mutableStateListOf<EditableItem>() }
    var listIds by remember { mutableStateOf(setOf<String>()) }
    var showColors by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    // The row whose text field should grab focus next (a just-added checklist item).
    var focusTargetLocalId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(noteId) {
        if (noteId == null) return@LaunchedEffect
        val n = repo.noteById(noteId) ?: repo.fetchNote(noteId)
        if (n == null) {
            onDone()
            return@LaunchedEffect
        }
        note = n
        type = n.type
        title = n.title ?: ""
        body = TextFieldValue(n.body ?: "")
        color = n.color
        items.clear()
        n.checklistItems.sortedBy { it.order }.forEach { items.add(EditableItem(it.id, it.text, it.isChecked)) }
        listIds = n.listIds.toSet()
        loaded = true
    }

    val canEdit = note?.canEdit ?: true
    val swatch = noteSwatch(color)

    fun addItemAfter(index: Int) {
        val newItem = EditableItem(null, "", false)
        items.add((index + 1).coerceAtMost(items.size), newItem)
        focusTargetLocalId = newItem.localId
    }

    fun switchToChecklist() {
        type = NoteTypes.CHECKLIST
        if (items.isEmpty()) {
            val first = EditableItem(null, "", false)
            items.add(first)
            focusTargetLocalId = first.localId
        }
    }

    fun buildChecklist(): List<ChecklistItemDto> = items
        .filter { it.text.isNotBlank() }
        .mapIndexed { index, item ->
            ChecklistItemDto(id = item.id, text = item.text.trim(), isChecked = item.isChecked, order = index)
        }

    fun saveAndClose() {
        val current = note
        scope.launch {
            if (current == null) {
                val checklist = buildChecklist()
                val hasContent = title.isNotBlank() || body.text.isNotBlank() || checklist.isNotEmpty()
                if (hasContent) {
                    repo.create(
                        CreateNoteDto(
                            type = type,
                            title = title.trim().ifBlank { null },
                            body = if (type == NoteTypes.TEXT) body.text.trim().ifBlank { null } else null,
                            color = color,
                            checklistItems = if (type == NoteTypes.CHECKLIST) checklist else null,
                            listIds = listIds.toList().ifEmpty { null },
                        ),
                    )
                }
            } else {
                if (canEdit) {
                    repo.update(
                        current.id,
                        UpdateNoteDto(
                            type = type,
                            title = title.trim().ifBlank { null },
                            body = if (type == NoteTypes.TEXT) body.text.trim().ifBlank { null } else null,
                            color = color,
                            checklistItems = if (type == NoteTypes.CHECKLIST) buildChecklist() else null,
                        ),
                    )
                }
                if (listIds != current.listIds.toSet()) {
                    repo.setLists(current.id, listIds.toList())
                }
            }
            onDone()
        }
    }

    BackHandler { saveAndClose() }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = swatch.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = swatch.bg,
                    titleContentColor = KeepItColors.Text,
                ),
                navigationIcon = {
                    IconButton(onClick = ::saveAndClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Save and close",
                            tint = KeepItColors.TextMuted,
                        )
                    }
                },
                title = {},
                actions = {
                    val current = note
                    if (current != null) {
                        IconButton(onClick = {
                            scope.launch { repo.setState(current.id, NoteStateDto(isPinned = !current.isPinned)) }
                            note = current.copy(isPinned = !current.isPinned)
                        }) {
                            Icon(
                                imageVector = if (current.isPinned) Icons.Filled.Star else Icons.Outlined.Star,
                                contentDescription = if (current.isPinned) "Unpin" else "Pin",
                                tint = if (current.isPinned) KeepItColors.Accent else KeepItColors.TextMuted,
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = KeepItColors.TextMuted)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (current.isArchived) "Unarchive" else "Archive") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        repo.setState(current.id, NoteStateDto(isArchived = !current.isArchived))
                                        onDone()
                                    }
                                },
                            )
                            if (current.isOwner && current.isTrashed) {
                                DropdownMenuItem(
                                    text = { Text("Delete forever") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            repo.delete(current.id)
                                            onDone()
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            val current = note
            // Editors get the color + type tools; any existing note (viewers too) gets trash/restore.
            if (canEdit || current != null) {
                Column(modifier = Modifier.imePadding()) {
                    // Markdown formatting row — text notes only; each button rewrites the selection.
                    if (canEdit && type == NoteTypes.TEXT) {
                        HorizontalDivider(color = swatch.border)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(swatch.bg)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FormatButton(Icons.Filled.FormatBold, "Bold") {
                                body = applyMarkdown(body, MarkdownAction.BOLD)
                            }
                            FormatButton(Icons.Filled.FormatItalic, "Italic") {
                                body = applyMarkdown(body, MarkdownAction.ITALIC)
                            }
                            FormatButton(Icons.Filled.StrikethroughS, "Strikethrough") {
                                body = applyMarkdown(body, MarkdownAction.STRIKE)
                            }
                            FormatButton(Icons.Filled.Title, "Heading") {
                                body = applyMarkdown(body, MarkdownAction.HEADING)
                            }
                            FormatButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list") {
                                body = applyMarkdown(body, MarkdownAction.BULLET)
                            }
                            FormatButton(Icons.Filled.FormatListNumbered, "Numbered list") {
                                body = applyMarkdown(body, MarkdownAction.ORDERED)
                            }
                            FormatButton(Icons.Filled.Link, "Link") {
                                body = applyMarkdown(body, MarkdownAction.LINK)
                            }
                            FormatButton(Icons.Filled.Code, "Code") {
                                body = applyMarkdown(body, MarkdownAction.CODE)
                            }
                        }
                    }
                    HorizontalDivider(color = swatch.border)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(swatch.bg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canEdit) {
                            // Background color picker toggle (web parity: Palette in the footer).
                            IconButton(onClick = { showColors = !showColors }) {
                                Icon(
                                    Icons.Filled.Palette,
                                    contentDescription = "Background color",
                                    tint = if (showColors) KeepItColors.Accent else KeepItColors.TextMuted,
                                )
                            }
                            // Text ⇄ checklist toggle (web parity: CheckSquare in the footer).
                            IconButton(onClick = {
                                if (type == NoteTypes.TEXT) switchToChecklist() else type = NoteTypes.TEXT
                            }) {
                                Icon(
                                    Icons.Filled.Checklist,
                                    contentDescription = if (type == NoteTypes.CHECKLIST) "Switch to text" else "Switch to checklist",
                                    tint = if (type == NoteTypes.CHECKLIST) KeepItColors.Accent else KeepItColors.TextMuted,
                                )
                            }
                        }
                        // Reminders are per-user (read access suffices) — existing notes only,
                        // since a reminder needs a note id to attach to.
                        if (current != null && !current.isTrashed) {
                            IconButton(onClick = { showReminder = true }) {
                                Icon(
                                    Icons.Filled.Alarm,
                                    contentDescription = "Remind me",
                                    tint = if (current.remindAtUtc != null) KeepItColors.Accent else KeepItColors.TextMuted,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        // Move to trash / restore — right-aligned action, like the web card's trash tool.
                        if (current != null) {
                            IconButton(onClick = {
                                val trashing = !current.isTrashed
                                scope.launch {
                                    repo.setState(current.id, NoteStateDto(isTrashed = trashing))
                                    onDone()
                                }
                            }) {
                                Icon(
                                    imageVector = if (current.isTrashed) Icons.Filled.Restore else Icons.Filled.Delete,
                                    contentDescription = if (current.isTrashed) "Restore" else "Move to trash",
                                    tint = KeepItColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!loaded) {
            Box(modifier = Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            note?.let { n ->
                if (!n.isOwner) {
                    Text(
                        text = if (n.canEdit) "Shared with you — you can edit" else "Shared with you — view only",
                        color = KeepItColors.TextFaint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (n.remindAtUtc != null) {
                    ReminderChip(
                        note = n,
                        onClick = { showReminder = true },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }

            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Title", color = KeepItColors.TextFaint) },
                readOnly = !canEdit,
                colors = transparentFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = KeepItColors.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (type == NoteTypes.CHECKLIST) {
                items.forEachIndexed { index, item ->
                    // Focus a freshly added row once it's composed (Enter / Add item).
                    LaunchedEffect(Unit) {
                        if (item.localId == focusTargetLocalId) {
                            runCatching { item.focusRequester.requestFocus() }
                            focusTargetLocalId = null
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = item.isChecked,
                            onCheckedChange = { if (canEdit) item.isChecked = it },
                            enabled = canEdit,
                            colors = CheckboxDefaults.colors(
                                checkedColor = KeepItColors.Accent,
                                checkmarkColor = Color.Black,
                                uncheckedColor = KeepItColors.BorderStrong,
                            ),
                        )
                        TextField(
                            value = item.text,
                            onValueChange = { item.text = it },
                            placeholder = { Text("List item", color = KeepItColors.TextFaint) },
                            readOnly = !canEdit,
                            singleLine = true,
                            colors = transparentFieldColors(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp,
                                color = if (item.isChecked) KeepItColors.TextFaint else KeepItColors.Text,
                            ),
                            // Enter adds a new item right below and moves the cursor there.
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { addItemAfter(index) }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(item.focusRequester),
                        )
                        if (canEdit) {
                            IconButton(onClick = { items.removeAt(index) }) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "Remove item",
                                    tint = KeepItColors.TextFaint,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
                if (canEdit) {
                    androidx.compose.material3.TextButton(onClick = { addItemAfter(items.lastIndex) }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = KeepItColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("  Add item", color = KeepItColors.TextMuted)
                    }
                }
            } else if (!canEdit) {
                // Viewers get the rendered note, not raw Markdown in a disabled field.
                MarkdownText(
                    source = body.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                TextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("Take a note…", color = KeepItColors.TextFaint) },
                    colors = transparentFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = KeepItColors.Text,
                    ),
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.size(16.dp))

            if (canEdit && showColors) {
                // Color palette — same swatches as the web ColorPicker, themed for dark.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(NotePalette, key = { it.key }) { option ->
                        val selected = (color ?: "default") == option.key
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(option.bg, CircleShape)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) KeepItColors.Accent else option.border,
                                    shape = CircleShape,
                                )
                                .clickable { color = if (option.key == "default") null else option.key },
                        )
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))
            }

            if (lists.isNotEmpty()) {
                Text(
                    text = "LISTS",
                    color = KeepItColors.TextFaint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.size(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lists, key = { it.id }) { list ->
                        val selected = list.id in listIds
                        FilterChip(
                            selected = selected,
                            onClick = { listIds = if (selected) listIds - list.id else listIds + list.id },
                            label = { Text(list.name) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.size(48.dp))
        }
    }

    // Reminder picker — applies immediately (per-user state, independent of save-on-close).
    note?.let { current ->
        if (showReminder) {
            ReminderDialog(
                note = current,
                onSave = { dto ->
                    scope.launch {
                        repo.setReminder(current.id, dto)
                        note = repo.noteById(current.id) ?: current
                    }
                },
                onClear = {
                    scope.launch {
                        repo.clearReminder(current.id)
                        note = repo.noteById(current.id) ?: current
                    }
                },
                onDismiss = { showReminder = false },
            )
        }
    }
}

/** One button in the Markdown formatting row. */
@Composable
private fun FormatButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = KeepItColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}

/** Transparent text fields on the note-colored canvas, like the web editor's borderless inputs. */
@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = KeepItColors.Accent,
)
