package org.hyperstarit.keepitapp.ui.notes

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.ui.markdown.MarkdownText
import org.hyperstarit.keepitapp.ui.markdown.MarkdownVisualTransformation
import org.hyperstarit.keepitapp.ui.markdown.applyMarkdown
import org.hyperstarit.keepitapp.ui.markdown.continueListOnEnter
import org.hyperstarit.keepitapp.data.ChecklistItemDto
import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.AudioRecorder
import org.hyperstarit.keepitapp.data.MediaKinds
import org.hyperstarit.keepitapp.data.NoteTypes
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.LocalKeepItPalette
import org.hyperstarit.keepitapp.ui.theme.noteSwatch

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
 * Display order for the editor's rows: **unchecked first, checked at the bottom**, each group
 * keeping its stored order. The receiver stays in *home* order — it changes only when rows are
 * added, removed or reordered, never when a box is ticked — so checking a row sinks it and
 * unchecking puts it back in exactly the slot it came from. `buildChecklist` persists that home
 * order and the sort is stable, so it survives a round-trip through the server.
 *
 * Each row is paired with its index in the home list, because that (not the display position) is
 * what every edit addresses. The read-only surfaces apply the same rule to DTOs — see
 * `List<ChecklistItemDto>.inDisplayOrder()` in `data/Checklist.kt` — as does the web editor.
 */
private fun List<EditableItem>.displayRows(): List<IndexedValue<EditableItem>> =
    withIndex().sortedBy { it.value.isChecked }

/**
 * Full-note editor, the phone twin of the web NoteEditorModal: title, body or checklist. Tools that
 * add to the note float at the bottom ([EditorToolbar]); actions on the note — reminder, share, pin,
 * archive, trash — are in the top bar. Saves on leaving (back button or the top-left arrow) rather
 * than with an explicit save; viewers see content read-only but may still file the note into their
 * own lists (list membership is per-user).
 *
 * With a null [noteId] it's the composer — the widget's "+" lands here, as does text shared in from
 * another app (via [initialTitle] / [initialBody], ignored when editing an existing note).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    container: AppContainer,
    noteId: String?,
    onDone: () -> Unit,
    initialTitle: String? = null,
    initialBody: String? = null,
) {
    val repo = container.notesRepo
    val scope = rememberCoroutineScope()
    val lists by repo.lists.collectAsState()
    // Standalone: no sharing, and queued images are the note's images rather than uploads.
    val standalone by container.appMode.standalone.collectAsState()

    // Existing note: prefer the grid cache, fall back to a direct fetch (widget deep link).
    var loaded by remember { mutableStateOf(noteId == null) }
    var note by remember { mutableStateOf<NoteDto?>(null) }

    var type by remember { mutableStateOf(NoteTypes.TEXT) }
    // Shared-in text seeds the composer; ignored for an existing note (its content loads below).
    var title by remember { mutableStateOf(if (noteId == null) initialTitle.orEmpty() else "") }
    // TextFieldValue (not String) so the Markdown toolbar can rewrite the current selection.
    var body by remember {
        val seed = if (noteId == null) initialBody.orEmpty() else ""
        mutableStateOf(TextFieldValue(text = seed, selection = TextRange(seed.length)))
    }
    var color by remember { mutableStateOf<String?>(null) }
    val items = remember { mutableStateListOf<EditableItem>() }
    var listIds by remember { mutableStateOf(setOf<String>()) }
    var showColorSheet by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    // The row whose text field should grab focus next (a just-added checklist item).
    var focusTargetLocalId by remember { mutableStateOf<Long?>(null) }

    // ---- images ----
    val context = LocalContext.current
    // Live view of this note, so an attachment that lands (or replays from the outbox) shows up
    // without reopening the editor.
    val allNotes by repo.allNotes.collectAsState()
    // Images picked but not yet attached, so a photo-only note still counts as having content and
    // gets created rather than discarded as empty.
    var attachIntent by remember { mutableStateOf(0) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // Why an image didn't make it: unreadable here, or turned down by the server on upload (too
    // large, HEIC). The notes grid reports sync failures too, but it isn't composed while the editor
    // is open, so a refused image used to show as uploading and then just vanish, reason unsaid.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        container.syncEngine.syncErrors.collect { snackbarHostState.showSnackbar(it) }
    }

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

    // This note as the cache has it right now, looked up through the id alias rather than by
    // `note.id`: a note born in this editor keeps its temp id in `note` for the whole session, while
    // its first sync moves the cached copy over to the server's id. Matching the ids directly lost it
    // there and fell back to the snapshot taken at creation — which has no images — so a photo
    // attached to a new note vanished the moment it finished uploading.
    val live = note?.let { n -> allNotes.find { it.id == repo.resolve(n.id) } ?: n }

    // Attachments still in the outbox, looked up by the resolved id for the same reason as `live`.
    val pendingByNote by repo.pendingMediaByNote.collectAsState()
    val pending = note?.let { n -> pendingByNote[repo.resolve(n.id)] }.orEmpty()

    val canEdit = note?.canEdit ?: true
    val swatch = noteSwatch(color)

    // Queued images count: the server enforces the limit on upload, and in standalone mode they are
    // all the images there are.
    val atImageLimit = (live?.media?.count { !it.isAudio } ?: 0) +
        pending.count { !it.isAudio } >= MAX_IMAGES_PER_NOTE

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

    // One serialized save path. Persisting is local + instant (offline outbox), so it's cheap to run
    // often — on every edit (debounced) and whenever the app is backgrounded, not only on close.
    // Saving only on close was the bug: a screen lock mid-edit backgrounds us without closing, and if
    // the process is later reclaimed the in-progress checkbox/text edits (held only in UI state) were
    // lost. The first save of a new note creates it and captures the result, so subsequent saves
    // update that note instead of creating duplicates; the mutex keeps overlapping saves from racing.
    val saveMutex = remember { Mutex() }

    // Both representations are always persisted, whatever `type` currently is. The server stores
    // Body and ChecklistItems independently and `type` only selects which one renders, so keeping
    // the inactive side makes the Text ↔ Checklist toggle reversible. Nulling it out instead meant
    // one tap on the toolbar deleted every checklist row (the server replaces the set wholesale) —
    // and because saving here is autosaved on a debounce, it happened without even closing the note.
    suspend fun persist() = saveMutex.withLock {
        val current = note
        if (current == null) {
            val checklist = buildChecklist()
            // A note that will hold nothing but photos still has content worth saving.
            val hasContent =
                title.isNotBlank() || body.text.isNotBlank() || checklist.isNotEmpty() || attachIntent > 0
            if (!hasContent) return@withLock
            note = repo.create(
                CreateNoteDto(
                    type = type,
                    title = title.trim().ifBlank { null },
                    body = body.text.trim().ifBlank { null },
                    color = color,
                    checklistItems = checklist.ifEmpty { null },
                    listIds = listIds.toList().ifEmpty { null },
                ),
            )
        } else {
            if (current.canEdit) {
                repo.update(
                    current.id,
                    UpdateNoteDto(
                        type = type,
                        title = title.trim().ifBlank { null },
                        body = body.text.trim().ifBlank { null },
                        color = color,
                        checklistItems = buildChecklist(),
                    ),
                )
            }
            if (listIds != current.listIds.toSet()) {
                repo.setLists(current.id, listIds.toList())
                note = repo.noteById(current.id) ?: current
            }
        }
    }

    fun saveAndClose() {
        scope.launch {
            persist()
            onDone()
        }
    }

    /**
     * Attaches picked images, creating the note first when this is the composer — an attachment
     * needs a note id, and the id only exists once the note is saved.
     */
    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            attachIntent += uris.size
            persist()
            val id = note?.id
            if (id == null) {
                attachIntent = 0
                return@launch
            }
            val unreadable = uris.count { !repo.attachMedia(context, id, it) }
            attachIntent = 0
            if (unreadable > 0) {
                snackbarHostState.showSnackbar(
                    if (unreadable == 1) "Couldn't read that image." else "Couldn't read $unreadable images.",
                )
            }
        }
    }

    /**
     * Records a voice note, then attaches it exactly as a picked image is attached.
     *
     * Everything past the recorder is shared with images: the same staging copy, the same outbox
     * op, the same upload. A recording made with no signal, or in standalone mode, therefore
     * survives and syncs for free - which is why recording needed no new offline machinery.
     */
    val recorder = remember { AudioRecorder(context) }
    var recordingSince by remember { mutableStateOf<Long?>(null) }
    var recordingElapsed by remember { mutableIntStateOf(0) }

    // A timer while recording: the one thing that tells the user the microphone is actually live.
    LaunchedEffect(recordingSince) {
        val startedAt = recordingSince ?: return@LaunchedEffect
        while (true) {
            recordingElapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            delay(250)
        }
    }

    fun attachRecording(file: java.io.File) {
        scope.launch {
            attachIntent += 1
            persist()
            val id = note?.id
            if (id == null) {
                attachIntent = 0
                file.delete()
                return@launch
            }
            val ok = repo.attachMedia(context, id, Uri.fromFile(file), MediaKinds.AUDIO)
            attachIntent = 0
            // The staged copy is the one that matters from here; this was only the scratch file.
            file.delete()
            if (!ok) snackbarHostState.showSnackbar("Couldn't save that recording.")
        }
    }

    fun stopRecording(keep: Boolean) {
        recordingSince = null
        recordingElapsed = 0
        val file = if (keep) recorder.stop() else null.also { recorder.cancel() }
        if (keep && file == null) {
            scope.launch { snackbarHostState.showSnackbar("That recording was too short.") }
            return
        }
        file?.let(::attachRecording)
    }

    fun beginRecording() {
        if (recorder.start(onLimitReached = { stopRecording(keep = true) }) != null) {
            recordingSince = System.currentTimeMillis()
        } else {
            scope.launch { snackbarHostState.showSnackbar("Couldn't start recording.") }
        }
    }

    val askForMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginRecording()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Voice notes need access to the microphone.")
            }
        }
    }

    fun toggleRecording() {
        if (recordingSince != null) {
            stopRecording(keep = true)
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) beginRecording() else askForMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Leaving the editor mid-recording must not leave the microphone open.
    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    // The system photo picker needs no runtime permission at all.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_IMAGES_PER_NOTE),
    ) { uris -> attachImages(uris) }

    // Camera capture writes into our own cache dir and is handed out via FileProvider. We
    // deliberately do NOT declare the CAMERA permission: ACTION_IMAGE_CAPTURE doesn't need it, and
    // declaring it would force a permission prompt for nothing.
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) captureUri?.let { attachImages(listOf(it)) }
        captureUri = null
    }

    fun launchCamera() {
        val file = File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        captureUri = uri
        takePhoto.launch(uri)
    }

    BackHandler { saveAndClose() }

    // Autosave: debounce edits so typing and checkbox toggles reach the outbox within a moment,
    // instead of living only in volatile UI state until the editor closes.
    val editSignature = editSignatureOf(type, title, body.text, color, listIds, items)
    var savedSignature by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loaded) {
        // Baseline the loaded content so merely opening a note doesn't enqueue a redundant save.
        if (loaded && savedSignature == null) savedSignature = editSignature
    }
    LaunchedEffect(editSignature) {
        if (!loaded || savedSignature == null || editSignature == savedSignature) return@LaunchedEffect
        delay(600)
        persist()
        savedSignature = editSignature
    }

    // A screen lock or app switch backgrounds us (ON_STOP) without going through close — flush
    // immediately so in-progress edits survive even if the process is reclaimed while away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) scope.launch { persist() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var menuOpen by remember { mutableStateOf(false) }
    val palette = LocalKeepItPalette.current
    val markdownStyling = remember(palette) { MarkdownVisualTransformation(palette) }

    Scaffold(
        containerColor = swatch.bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        // Reminders are per-user (read access suffices) — existing notes only,
                        // since a reminder needs a note id to attach to.
                        if (!current.isTrashed) {
                            IconButton(onClick = { showReminder = true }) {
                                Icon(
                                    Icons.Filled.Alarm,
                                    contentDescription = "Remind me",
                                    tint = if (current.remindAtUtc != null) KeepItColors.AccentInk else KeepItColors.TextMuted,
                                )
                            }
                            // Share management — owners invite/revoke, collaborators see & leave.
                            // Sharing is between accounts on a server, so standalone has none.
                            if (!standalone) {
                                IconButton(onClick = { showShare = true }) {
                                    Icon(
                                        Icons.Filled.PersonAdd,
                                        contentDescription = "Share note",
                                        tint = if (current.isShared) KeepItColors.AccentInk else KeepItColors.TextMuted,
                                    )
                                }
                            }
                        }
                        IconButton(onClick = {
                            scope.launch { repo.setState(current.id, NoteStateDto(isPinned = !current.isPinned)) }
                            note = current.copy(isPinned = !current.isPinned)
                        }) {
                            Icon(
                                imageVector = if (current.isPinned) Icons.Filled.Star else Icons.Outlined.Star,
                                contentDescription = if (current.isPinned) "Unpin" else "Pin",
                                tint = if (current.isPinned) KeepItColors.AccentInk else KeepItColors.TextMuted,
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
                            // Trash is per-user state, so viewers may trash (and restore) too.
                            // It lives here rather than among the tools, a slip away from the camera.
                            DropdownMenuItem(
                                text = { Text(if (current.isTrashed) "Restore" else "Move to trash") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        repo.setState(current.id, NoteStateDto(isTrashed = !current.isTrashed))
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
            // Editors only: every tool here adds to the note. Viewers reach the rest from the top bar.
            if (canEdit) {
                EditorToolbar(
                    canFormat = type == NoteTypes.TEXT,
                    isChecklist = type == NoteTypes.CHECKLIST,
                    hasColor = color != null,
                    recording = recordingSince != null,
                    onAdd = { showAddSheet = true },
                    onColor = { showColorSheet = true },
                    onToggleChecklist = {
                        if (type == NoteTypes.TEXT) switchToChecklist() else type = NoteTypes.TEXT
                    },
                    onToggleRecording = ::toggleRecording,
                    // Each button rewrites the body's current selection.
                    onFormat = { action -> body = applyMarkdown(body, action) },
                )
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

            if (recordingSince != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Recording  ${formatDuration(recordingElapsed * 1000)}",
                        color = KeepItColors.Text,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    TextButton(onClick = { stopRecording(keep = false) }) {
                        Text("Discard", color = KeepItColors.TextMuted)
                    }
                    TextButton(onClick = { stopRecording(keep = true) }) {
                        Text("Stop")
                    }
                }
            }

            live?.let { n ->
                MediaRow(
                    cache = repo.mediaCache,
                    noteId = n.id,
                    media = n.media,
                    pending = pending,
                    canEdit = canEdit,
                    onRemove = { mediaId -> scope.launch { repo.removeMedia(n.id, mediaId) } },
                    onOpen = { index -> viewerIndex = index },
                    modifier = Modifier.padding(bottom = 4.dp),
                    keptOnDevice = standalone,
                    onRemovePending = { op -> scope.launch { repo.removePendingMedia(op) } },
                )

                viewerIndex?.let { index ->
                    // Same order as the row: stored images first, then (standalone) the device's own.
                    // Matches the strip's own order and filtering, so the index the row hands
                    // over lands on the picture the user actually tapped.
                    val images = n.media.filter { !it.isAudio }.map { ViewerImage.Stored(it) } +
                        if (standalone) {
                            pending.filter { !it.isAudio }.map { ViewerImage.OnDevice(it) }
                        } else {
                            emptyList()
                        }
                    MediaViewer(
                        cache = repo.mediaCache,
                        noteId = n.id,
                        images = images,
                        startIndex = index,
                        onClose = { viewerIndex = null },
                        onSave = { image ->
                            when (image) {
                                is ViewerImage.Stored -> repo.saveMediaToGallery(n.id, image.media.id)
                                is ViewerImage.OnDevice -> repo.savePendingMediaToGallery(image.attachment)
                            }
                        },
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
                // Rendered in display order; `index` is the row's index in `items` (home order),
                // which every edit below addresses.
                items.displayRows().forEach { (index, item) ->
                    // Keyed by the row's stable local id so a row that moves (ticking sinks it to
                    // the bottom) carries its composable state — and its focus — along with it.
                    key(item.localId) {
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
                    // Enter inside a list starts the next item (or ends the list on an empty one).
                    onValueChange = { body = continueListOnEnter(body, it) },
                    // The raw Markdown, styled in place: what is being written reads as it will look.
                    visualTransformation = markdownStyling,
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

    // Share sheet — server-backed, independent of save-on-close.
    note?.let { current ->
        if (showShare) {
            ShareSheet(
                container = container,
                note = current,
                onDismiss = { showShare = false },
                onLeft = {
                    showShare = false
                    onDone()
                },
            )
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

    if (canEdit && showAddSheet) {
        AddToNoteSheet(
            imagesAtLimit = atImageLimit,
            recording = recordingSince != null,
            onDismiss = { showAddSheet = false },
            onTakePhoto = ::launchCamera,
            onPickImages = {
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onToggleRecording = ::toggleRecording,
        )
    }

    if (canEdit && showColorSheet) {
        NoteColorSheet(
            selected = color,
            onPick = { color = it },
            onDismiss = { showColorSheet = false },
        )
    }
}

/**
 * A cheap fingerprint of everything the editor persists, so the autosave effect only fires on a real
 * content change (not on every recomposition). Includes each checklist item's id, checked state, and
 * text so toggling a box or editing a row counts as an edit.
 */
private fun editSignatureOf(
    type: String,
    title: String,
    body: String,
    color: String?,
    listIds: Set<String>,
    items: List<EditableItem>,
): String {
    val parts = buildList {
        add(type)
        add(title)
        add(body)
        add(color ?: "")
        add(listIds.sorted().joinToString(","))
        items.forEach { add("${it.id ?: "*"}=${it.isChecked}:${it.text}") }
    }
    // Length-prefix each field so no separator char is needed and no two distinct field
    // sets can collide into the same fingerprint.
    return parts.joinToString("") { "${it.length}:$it" }
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
    cursorColor = KeepItColors.AccentInk,
)
