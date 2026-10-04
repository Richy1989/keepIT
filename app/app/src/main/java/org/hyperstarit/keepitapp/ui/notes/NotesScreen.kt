package org.hyperstarit.keepitapp.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SyncProblem as SyncProblemIcon
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star as StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.NotesFilter
import org.hyperstarit.keepitapp.data.NotesView
import org.hyperstarit.keepitapp.data.SessionState
import org.hyperstarit.keepitapp.data.UserDto
import org.hyperstarit.keepitapp.data.offline.SyncProblem
import org.hyperstarit.keepitapp.data.offline.SyncStatus
import org.hyperstarit.keepitapp.data.offline.membershipOf
import org.hyperstarit.keepitapp.ui.auth.UnsyncedSignOutDialog
import org.hyperstarit.keepitapp.ui.settings.ProfilePicture
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.accentButtonColors
import java.io.File

/**
 * The phone twin of the web HomePage: a drawer with Notes/Archive/Trash + the user's lists (with
 * counts), a topbar with search, and a single-column note list split into Pinned/Others in the
 * active view. Realtime keeps it live; pull-to-refresh (and the topbar refresh action) is a
 * manual resync. The drawer ends in the signed-in account and Sign out, pinned below its
 * scrolling nav.
 *
 * In standalone mode there is nothing to sync with or sign out of: refresh, sign-out, the sync
 * strip and the server inbox all go, and the drawer says where the notes live.
 *
 * Long-pressing a card starts a multi-select, Android's usual way: taps then select, the top bar
 * becomes the selection's actions (pin, color, lists, trash, archive; restore or delete forever in
 * the trash), and Back or the close button ends it. Every action ends it too, and the ones that
 * move notes out of sight offer an Undo. All of it goes through the repository as the single-note
 * ops would, so it works offline and in standalone mode alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    container: AppContainer,
    onOpenNote: (String) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
    val repo = container.notesRepo
    val scope = rememberCoroutineScope()
    // One player for the whole list: scrolling a card away must not cut off the recording it is
    // playing, and two cards must never talk over each other. See CardAudioPlayer.
    val cardAudio = remember { CardAudioPlayer(scope) }
    DisposableEffect(Unit) { onDispose { cardAudio.release() } }

    val notes by repo.notes.collectAsState()
    val lists by repo.lists.collectAsState()
    val filter by repo.filter.collectAsState()
    val loading by repo.loading.collectAsState()
    val isOnline by container.connectivity.isOnline.collectAsState()
    val syncProblem by container.connectivity.problem.collectAsState()
    val pending by container.pendingChanges.collectAsState()
    val syncStatus by container.syncEngine.status.collectAsState()
    val standalone by container.appMode.standalone.collectAsState()
    val pendingMedia by repo.pendingMediaByNote.collectAsState()
    val session by container.session.state.collectAsState()
    val profilePicture by container.profileImage.file.collectAsState()
    // Set once Sign out is tapped, so a second tap can't start a second sign-out.
    var signingOut by remember { mutableStateOf(false) }
    // Changes still queued make Sign out ask first: it tries one last sync, then wipes the queue
    // whether or not that got through.
    var confirmSignOut by remember { mutableStateOf(false) }
    fun signOut() {
        if (signingOut) return
        signingOut = true
        scope.launch { container.session.logout() }
    }

    var search by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Changes that permanently failed to sync (e.g. the note was deleted elsewhere) surface once.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        container.syncEngine.syncErrors.collect { snackbarHostState.showSnackbar(it) }
    }

    // List management — offline-first like notes; a change the server later refuses surfaces
    // through syncErrors above. The dialog being shown, if any.
    var newListOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ListDto?>(null) }
    var deleteTarget by remember { mutableStateOf<ListDto?>(null) }
    // "Delete all" in the trash: the notes it was pressed for, while the confirmation is up.
    var emptyTrashTarget by remember { mutableStateOf<List<NoteDto>?>(null) }

    // Multi-select: the selected notes' ids, saved so a rotation keeps the selection. The sheets
    // act on it live; [selectionEdited] records whether one changed anything, since closing a
    // sheet that did ends the selection and closing one that didn't leaves it be.
    var selectedIds by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(emptySet<String>()) }
    var colorSheetOpen by remember { mutableStateOf(false) }
    var listsSheetOpen by remember { mutableStateOf(false) }
    var newListForSelection by remember { mutableStateOf(false) }
    var selectionEdited by remember { mutableStateOf(false) }
    // "Delete forever" on a selection in the trash, while its confirmation is up.
    var purgeTarget by remember { mutableStateOf<List<NoteDto>?>(null) }

    fun applyFilter(newFilter: NotesFilter) {
        scope.launch {
            repo.setFilter(newFilter)
            drawerState.close()
        }
    }

    val q = search.trim().lowercase()
    val visible = if (q.isEmpty()) notes else notes.filter { it.matchesSearch(q) }
    val showSections = filter.view == NotesView.ACTIVE && q.isEmpty()
    val pinned = if (showSections) visible.filter { it.isPinned } else emptyList()
    val others = if (showSections) visible.filter { !it.isPinned } else visible

    val selectedNotes = visible.filter { it.id in selectedIds }
    val selecting = selectedNotes.isNotEmpty()
    // A selected note can leave the list under the selection (trashed on another device, or
    // searched out of view), and one created offline changes id when it syncs. The selection keeps
    // to what is on screen, under the ids the notes go by now.
    val visibleIds = visible.mapTo(HashSet()) { it.id }
    LaunchedEffect(visibleIds) {
        val kept = selectedIds.mapTo(HashSet(), repo::resolve).filterTo(HashSet()) { it in visibleIds }
        if (kept != selectedIds) selectedIds = kept
    }

    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    // Back leaves the selection before it leaves the screen.
    BackHandler(enabled = selecting) { clearSelection() }

    /**
     * Applies [state] to the notes in [changed] and ends the selection. With a [message], a
     * snackbar offers to put back [undo] — on exactly those notes, not the ones that were already
     * that way.
     */
    fun changeSelection(changed: List<String>, state: NoteStateDto, message: String? = null, undo: NoteStateDto? = null) {
        clearSelection()
        if (changed.isEmpty()) return
        scope.launch {
            repo.setStateOf(changed, state)
            if (message == null || undo == null) return@launch
            val result = snackbarHostState.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) repo.setStateOf(changed, undo)
        }
    }

    fun endSheet() {
        if (selectionEdited) clearSelection()
        selectionEdited = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // A swipe from the edge mid-selection would change the view out from under it.
        gesturesEnabled = !selecting,
        drawerContent = {
            // Given the drawer state, the sheet handles Back itself: an open drawer closes (following
            // a predictive back gesture) instead of Back falling through and finishing the activity.
            ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = KeepItColors.Surface) {
                // The nav scrolls on its own so a long run of lists never pushes the account
                // footer off the bottom of the sheet.
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "keepIT",
                        color = KeepItColors.AccentInk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = 20.dp,
                            bottom = if (standalone) 2.dp else 20.dp,
                        ),
                    )
                    if (standalone) {
                        Text(
                            text = "Standalone — notes stay on this device",
                            color = KeepItColors.TextFaint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text("Notes") },
                        selected = filter.view == NotesView.ACTIVE && filter.listIds.isEmpty(),
                        onClick = { applyFilter(NotesFilter(NotesView.ACTIVE)) },
                    )
                    NavigationDrawerItem(
                        label = { Text("Reminders") },
                        selected = filter.view == NotesView.REMINDERS,
                        onClick = { applyFilter(NotesFilter(NotesView.REMINDERS)) },
                    )
                    NavigationDrawerItem(
                        label = { Text("Archive") },
                        selected = filter.view == NotesView.ARCHIVED,
                        onClick = { applyFilter(NotesFilter(NotesView.ARCHIVED)) },
                    )
                    NavigationDrawerItem(
                        label = { Text("Trash") },
                        selected = filter.view == NotesView.TRASHED,
                        onClick = { applyFilter(NotesFilter(NotesView.TRASHED)) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = KeepItColors.BorderSubtle,
                    )
                    Text(
                        text = "LISTS",
                        color = KeepItColors.TextFaint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    lists.forEach { list ->
                        val selected = filter.view == NotesView.ACTIVE && list.id in filter.listIds
                        var listMenuOpen by remember(list.id) { mutableStateOf(false) }
                        NavigationDrawerItem(
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(list.name, modifier = Modifier.weight(1f))
                                    Text("${list.noteCount}", color = KeepItColors.TextFaint)
                                }
                            },
                            badge = {
                                Box {
                                    IconButton(onClick = { listMenuOpen = true }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "List options",
                                            tint = KeepItColors.TextFaint,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = listMenuOpen,
                                        onDismissRequest = { listMenuOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = { listMenuOpen = false; renameTarget = list },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            onClick = { listMenuOpen = false; deleteTarget = list },
                                        )
                                    }
                                }
                            },
                            selected = selected,
                            onClick = {
                                val ids = if (selected) filter.listIds - list.id else filter.listIds + list.id
                                applyFilter(NotesFilter(NotesView.ACTIVE, ids))
                            },
                        )
                    }
                    NavigationDrawerItem(
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = KeepItColors.TextMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text("  New list", color = KeepItColors.TextMuted)
                            }
                        },
                        selected = false,
                        onClick = { newListOpen = true },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = KeepItColors.BorderSubtle,
                    )
                    // The inbox is the server's (share invites, server-fired reminders); standalone
                    // reminders post straight to the system tray instead.
                    if (!standalone) {
                        NavigationDrawerItem(
                            label = { Text("Notifications") },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                onOpenNotifications()
                            },
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
                        },
                    )
                }
                // Standalone has no account to leave: its "sign out" erases the device, and that
                // lives behind a confirmation in Settings instead.
                if (!standalone) {
                    DrawerAccountFooter(
                        user = (session as? SessionState.SignedIn)?.user,
                        picture = profilePicture,
                        onSignOut = { if (pending > 0) confirmSignOut = true else signOut() },
                    )
                }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    if (selecting) SelectionTopBar(
                        notes = selectedNotes,
                        view = filter.view,
                        allSelected = selectedNotes.size == visible.size,
                        onClear = ::clearSelection,
                        onSelectAll = { selectedIds = visibleIds },
                        onPin = {
                            val pin = selectedNotes.any { !it.isPinned }
                            changeSelection(
                                selectedNotes.filter { it.isPinned != pin }.map { it.id },
                                NoteStateDto(isPinned = pin),
                            )
                        },
                        onColor = { colorSheetOpen = true },
                        onLists = { listsSheetOpen = true },
                        onArchive = {
                            val archive = selectedNotes.any { !it.isArchived }
                            val changed = selectedNotes.filter { it.isArchived != archive }.map { it.id }
                            changeSelection(
                                changed,
                                NoteStateDto(isArchived = archive),
                                message = "${notesLabel(changed.size)} ${if (archive) "archived" else "unarchived"}",
                                undo = NoteStateDto(isArchived = !archive),
                            )
                        },
                        onTrash = {
                            val changed = selectedNotes.map { it.id }
                            changeSelection(
                                changed,
                                NoteStateDto(isTrashed = true),
                                message = "${notesLabel(changed.size)} moved to trash",
                                undo = NoteStateDto(isTrashed = false),
                            )
                        },
                        onRestore = {
                            val changed = selectedNotes.map { it.id }
                            changeSelection(
                                changed,
                                NoteStateDto(isTrashed = false),
                                message = "${notesLabel(changed.size)} restored",
                                undo = NoteStateDto(isTrashed = true),
                            )
                        },
                        onDeleteForever = { purgeTarget = selectedNotes },
                    ) else TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = KeepItColors.Text,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = KeepItColors.TextMuted)
                            }
                        },
                        title = {
                            Text(
                                text = titleFor(filter, lists.size),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp,
                            )
                        },
                        actions = {
                            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) search = "" }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search", tint = KeepItColors.TextMuted)
                            }
                            // Standalone has nothing to refresh from.
                            if (!standalone) {
                                IconButton(onClick = { scope.launch { repo.refreshAll() } }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = KeepItColors.TextMuted)
                                }
                            }
                        },
                    )
                    if (searchOpen) {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = { Text("Search notes…") },
                            singleLine = true,
                            trailingIcon = {
                                if (search.isNotEmpty()) {
                                    IconButton(onClick = { search = "" }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = KeepItColors.TextMuted)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    // Standalone changes are never "waiting to sync" — they are simply saved.
                    if (!standalone) {
                        SyncStatusStrip(isOnline = isOnline, problem = syncProblem, pending = pending, syncStatus = syncStatus)
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(visible = !selecting, enter = scaleIn(), exit = scaleOut()) {
                    FloatingActionButton(
                        onClick = onCompose,
                        containerColor = KeepItColors.Accent,
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New note")
                    }
                }
            },
        ) { padding ->
            // A tap opens a note, or while selecting picks it; a long press starts the selection.
            val card: @Composable (NoteDto) -> Unit = { note ->
                NoteCard(
                    note = note,
                    repo = repo,
                    audio = cardAudio,
                    pendingMedia = pendingMedia[note.id].orEmpty(),
                    selected = note.id in selectedIds,
                    selecting = selecting,
                    onClick = { if (selecting) toggleSelected(note.id) else onOpenNote(note.id) },
                    onLongClick = { toggleSelected(note.id) },
                )
            }
            val content: @Composable BoxScope.() -> Unit = {
                when {
                    (loading || syncStatus == SyncStatus.SYNCING) && notes.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = KeepItColors.AccentInk,
                    )

                    // Empty states get a scrollable box so the pull gesture still works.
                    visible.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                q.isNotEmpty() -> "No notes match your search."
                                !standalone && !isOnline && notes.isEmpty() ->
                                    syncProblem?.let { "${it.message}. Your notes appear once the app can sync." }
                                        ?: "You're offline — your notes appear once you've connected."
                                else -> emptyCopy(filter.view)
                            },
                            color = KeepItColors.TextMuted,
                            modifier = Modifier.padding(32.dp),
                        )
                    }

                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (pinned.isNotEmpty()) {
                            item { SectionLabel("PINNED") }
                            items(pinned, key = { "p-${it.id}" }) { note -> card(note) }
                            item { SectionLabel("OTHERS") }
                        }
                        items(others, key = { it.id }) { note -> card(note) }
                        // Below the last note, as on the web, rather than in the top bar: that is
                        // full already, and emptying the trash is rare enough to earn a scroll.
                        // Hidden while searching, where "all" would be ambiguous, and while
                        // selecting, where the bar already offers to delete what is chosen.
                        if (filter.view == NotesView.TRASHED && q.isEmpty() && !selecting) {
                            item(key = "delete-all") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OutlinedButton(onClick = { emptyTrashTarget = visible }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Delete all", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (standalone) {
                // Nothing to pull from.
                Box(modifier = Modifier.padding(padding).fillMaxSize(), content = content)
            } else {
                val pullState = rememberPullToRefreshState()
                var refreshing by remember { mutableStateOf(false) }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            repo.refreshAll()
                            refreshing = false
                        }
                    },
                    state = pullState,
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                            color = KeepItColors.AccentInk,
                        )
                    },
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    content = content,
                )
            }
        }
    }

    // ---- list management dialogs (create / rename / delete-confirm) ----

    if (newListOpen) {
        ListNameDialog(
            title = "New list",
            confirmLabel = "Create",
            initial = "",
            onConfirm = { name ->
                newListOpen = false
                scope.launch { repo.createList(name) }
            },
            onDismiss = { newListOpen = false },
        )
    }

    renameTarget?.let { target ->
        ListNameDialog(
            title = "Rename list",
            confirmLabel = "Rename",
            initial = target.name,
            onConfirm = { name ->
                renameTarget = null
                scope.launch { repo.renameList(target.id, name) }
            },
            onDismiss = { renameTarget = null },
        )
    }

    emptyTrashTarget?.let { target ->
        DeleteForeverDialog(
            notes = target,
            everything = true,
            onConfirm = {
                emptyTrashTarget = null
                scope.launch { repo.emptyTrash(target.map { it.id }) }
            },
            onDismiss = { emptyTrashTarget = null },
        )
    }

    // ---- multi-select: delete-forever confirmation, color and list sheets ----

    purgeTarget?.let { target ->
        DeleteForeverDialog(
            notes = target,
            everything = false,
            onConfirm = {
                purgeTarget = null
                clearSelection()
                // The same op as "Delete all": the user's own notes are deleted, and they leave
                // the ones shared with them, which a plain delete would be refused.
                scope.launch { repo.emptyTrash(target.map { it.id }) }
            },
            onDismiss = { purgeTarget = null },
        )
    }

    if (colorSheetOpen && selecting) {
        // A color is the note's content, so a view-only note keeps its own.
        val editable = selectedNotes.filter { it.canEdit }
        val colors = editable.map { it.color }.distinct()
        val viewOnly = selectedNotes.size - editable.size
        NoteColorSheet(
            selected = colors.singleOrNull(),
            mixed = colors.size > 1,
            footnote = when (viewOnly) {
                0 -> null
                1 -> "One of these notes is view-only and keeps its color."
                else -> "$viewOnly of these notes are view-only and keep their color."
            },
            onPick = { color ->
                selectionEdited = true
                val ids = selectedIds
                scope.launch { repo.recolor(ids, color) }
            },
            onDismiss = {
                colorSheetOpen = false
                endSheet()
            },
        )
    }

    if (listsSheetOpen && selecting) {
        SelectionListsSheet(
            lists = lists,
            notes = selectedNotes,
            onToggle = { listId, member ->
                selectionEdited = true
                val ids = selectedIds
                scope.launch { repo.setListMembership(ids, listId, member) }
            },
            onNewList = { newListForSelection = true },
            onDismiss = {
                listsSheetOpen = false
                endSheet()
            },
        )
    }

    if (newListForSelection) {
        ListNameDialog(
            title = "New list",
            confirmLabel = "Create",
            initial = "",
            onConfirm = { name ->
                newListForSelection = false
                selectionEdited = true
                val ids = selectedIds
                // Filed under the list's temp id; the outbox replays its create first.
                scope.launch { repo.setListMembership(ids, repo.createList(name), member = true) }
            },
            onDismiss = { newListForSelection = false },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = KeepItColors.Surface,
            title = { Text("Delete \"${target.name}\"?") },
            text = {
                Text(
                    "The list goes away; the notes filed in it are kept.",
                    color = KeepItColors.TextMuted,
                )
            },
            confirmButton = {
                Button(colors = accentButtonColors(), onClick = {
                    deleteTarget = null
                    scope.launch { repo.deleteList(target.id) }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = KeepItColors.TextMuted)
                }
            },
        )
    }

    if (confirmSignOut) {
        UnsyncedSignOutDialog(
            pending = pending,
            onConfirm = {
                confirmSignOut = false
                signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/**
 * Confirms deleting trashed notes for good: [everything] in the trash ("Delete all"), or the ones
 * selected there. Notes shared with the user can't be deleted by them, only left, so the copy says
 * what happens to those.
 */
@Composable
private fun DeleteForeverDialog(
    notes: List<NoteDto>,
    everything: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sharedWithMe = notes.count { !it.isOwner }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KeepItColors.Surface,
        title = {
            Text(
                when {
                    notes.size == 1 -> "Delete the note forever?"
                    everything -> "Delete all ${notes.size} notes forever?"
                    else -> "Delete ${notes.size} notes forever?"
                },
            )
        },
        text = {
            Text(
                text = "This can't be undone." + if (sharedWithMe == 0) "" else
                    " Notes others shared with you are only removed from your notes. Their owners keep them.",
                color = KeepItColors.TextMuted,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(if (everything) "Delete all" else "Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KeepItColors.TextMuted)
            }
        },
    )
}

/**
 * The top bar while notes are selected — Android's contextual action bar: a way out, how many, and
 * what can be done to all of them at once. In the trash that is restore and delete forever.
 * Elsewhere pin, color, lists and trash sit in the bar and archive in its menu, the rarest of them
 * on a phone-width bar. Pin and archive go Keep's way: if any selected note isn't pinned, the
 * action pins them all, and only when every one is pinned does it unpin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    notes: List<NoteDto>,
    view: NotesView,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onPin: () -> Unit,
    onColor: () -> Unit,
    onLists: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = KeepItColors.Elevated,
            titleContentColor = KeepItColors.Text,
        ),
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = "Clear selection", tint = KeepItColors.TextMuted)
            }
        },
        title = {
            Text(text = "${notes.size}", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        },
        actions = {
            if (view == NotesView.TRASHED) {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Restore", tint = KeepItColors.TextMuted)
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = "Delete forever", tint = KeepItColors.TextMuted)
                }
            } else {
                val unpin = notes.all { it.isPinned }
                IconButton(onClick = onPin) {
                    Icon(
                        imageVector = if (unpin) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (unpin) "Unpin" else "Pin",
                        tint = if (unpin) KeepItColors.AccentInk else KeepItColors.TextMuted,
                    )
                }
                // Disabled rather than hidden when every selected note is view-only: the bar keeps
                // its shape, and a color is the one action here a viewer can't take.
                IconButton(onClick = onColor, enabled = notes.any { it.canEdit }) {
                    Icon(
                        Icons.Filled.Palette,
                        contentDescription = "Background color",
                        tint = if (notes.any { it.canEdit }) KeepItColors.TextMuted else KeepItColors.TextFaint,
                    )
                }
                IconButton(onClick = onLists) {
                    Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "Lists", tint = KeepItColors.TextMuted)
                }
                IconButton(onClick = onTrash) {
                    Icon(Icons.Filled.Delete, contentDescription = "Move to trash", tint = KeepItColors.TextMuted)
                }
            }
            val archive = view != NotesView.TRASHED
            if (archive || !allSelected) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = KeepItColors.TextMuted)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (archive) {
                            DropdownMenuItem(
                                text = { Text(if (notes.all { it.isArchived }) "Unarchive" else "Archive") },
                                onClick = { menuOpen = false; onArchive() },
                            )
                        }
                        if (!allSelected) {
                            DropdownMenuItem(
                                text = { Text("Select all") },
                                onClick = { menuOpen = false; onSelectAll() },
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * Files the selection into lists or takes it out of them. Each box shows where the selected notes
 * stand — checked when all are in the list, unchecked when none is, a dash when only some are —
 * and a tap applies at once, as the color sheet does: a checked box takes them all out, anything
 * else puts them all in. "New list" makes one and files the selection straight into it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionListsSheet(
    lists: List<ListDto>,
    notes: List<NoteDto>,
    onToggle: (listId: String, member: Boolean) -> Unit,
    onNewList: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KeepItColors.Surface) {
        Column(modifier = Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            SheetTitle("Lists")
            lists.forEach { list ->
                val state = when (membershipOf(notes, list.id)) {
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
                            uncheckedColor = KeepItColors.BorderStrong,
                        ),
                    )
                    Text(
                        text = list.name,
                        color = KeepItColors.Text,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 20.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable(onClick = onNewList)
                    .padding(horizontal = 24.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = KeepItColors.TextMuted, modifier = Modifier.size(24.dp))
                Text(
                    text = "New list",
                    color = KeepItColors.TextMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
    }
}

/** Name prompt shared by create and rename. Confirm is disabled while the name is blank. */
@Composable
private fun ListNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KeepItColors.Surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), colors = accentButtonColors(), onClick = { onConfirm(name.trim()) }) {
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

/**
 * The foot of the drawer, below the scrolling nav: who is signed in — the web account menu's
 * header, with the same avatar: their profile [picture], else their initial — and Sign out. Sign out is inset and tinted with
 * the error colour so it reads as an action, not one more place to go. [user] is null only in
 * the moment the session is changing; the account line waits for it, Sign out doesn't.
 */
@Composable
private fun DrawerAccountFooter(user: UserDto?, picture: File?, onSignOut: () -> Unit) {
    HorizontalDivider(color = KeepItColors.BorderSubtle)
    if (user != null) {
        val name = user.displayName?.takeIf { it.isNotBlank() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 22dp puts the avatar's centre on the Sign out icon's (12dp inset + 16dp + 12dp).
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp).clip(CircleShape).background(KeepItColors.Elevated),
            ) {
                Text(
                    text = (name ?: user.email).take(1).uppercase().ifEmpty { "?" },
                    color = KeepItColors.TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                ProfilePicture(picture)
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = name ?: "Account",
                    color = KeepItColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = user.email,
                    color = KeepItColors.TextFaint,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val danger = MaterialTheme.colorScheme.error
    NavigationDrawerItem(
        icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
        label = { Text("Sign out", fontWeight = FontWeight.Medium) },
        selected = false,
        onClick = onSignOut,
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = danger.copy(alpha = 0.12f),
            unselectedIconColor = danger,
            unselectedTextColor = danger,
        ),
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .padding(top = 8.dp, bottom = 12.dp),
    )
}

/**
 * One slim line under the top bar, shown only when something is worth saying: offline (with the
 * count of changes waiting), or an active replay. Silent whenever the app is online and in sync.
 *
 * Offline while the phone has a network carries a [problem], and the line is that instead: "Can't
 * find keepit.example.com on this network" sends someone to their phone's DNS, where "Offline"
 * sent them to a server that was fine.
 */
@Composable
private fun SyncStatusStrip(isOnline: Boolean, problem: SyncProblem?, pending: Int, syncStatus: SyncStatus) {
    val changes = if (pending == 1) "1 change" else "$pending changes"
    val text = when {
        syncStatus == SyncStatus.SYNCING && pending > 0 -> "Syncing $changes…"
        !isOnline && problem != null && pending > 0 -> "${problem.message} — $changes waiting"
        !isOnline && problem != null -> problem.message
        !isOnline && pending > 0 -> "Offline — $changes will sync when you're back"
        !isOnline -> "Offline — changes will sync when you're back"
        pending > 0 -> "Waiting to sync $changes"
        else -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(KeepItColors.Surface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = when {
                isOnline -> Icons.Filled.CloudUpload
                problem != null -> Icons.Filled.SyncProblemIcon
                else -> Icons.Filled.CloudOff
            },
            contentDescription = null,
            tint = KeepItColors.TextFaint,
            modifier = Modifier.padding(end = 8.dp).size(14.dp),
        )
        Text(text = text, color = KeepItColors.TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = KeepItColors.TextFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

private fun titleFor(filter: NotesFilter, listCount: Int): String = when {
    filter.view == NotesView.ARCHIVED -> "Archive"
    filter.view == NotesView.TRASHED -> "Trash"
    filter.view == NotesView.REMINDERS -> "Reminders"
    filter.listIds.isNotEmpty() -> "Filtered"
    else -> "keepIT"
}

private fun emptyCopy(view: NotesView): String = when (view) {
    NotesView.ACTIVE -> "Notes you add appear here."
    NotesView.ARCHIVED -> "No archived notes."
    NotesView.TRASHED -> "Trash is empty."
    NotesView.REMINDERS -> "No notes with reminders."
}

/** Saves the selection across a rotation: a Bundle holds a list of strings, not a set. */
private val IdSetSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** "Note" or "3 notes", to start the snackbar that follows a multi-select action. */
private fun notesLabel(count: Int): String = if (count == 1) "Note" else "$count notes"

/** Client-side search over title, body, and checklist items — same rule as the web grid. */
private fun NoteDto.matchesSearch(q: String): Boolean =
    (title ?: "").lowercase().contains(q) ||
        (body ?: "").lowercase().contains(q) ||
        checklistItems.any { it.text.lowercase().contains(q) }
