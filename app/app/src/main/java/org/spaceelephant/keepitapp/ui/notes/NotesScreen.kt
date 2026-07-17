package org.spaceelephant.keepitapp.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.spaceelephant.keepitapp.AppContainer
import org.spaceelephant.keepitapp.data.ListDto
import org.spaceelephant.keepitapp.data.NoteDto
import org.spaceelephant.keepitapp.data.NotesFilter
import org.spaceelephant.keepitapp.data.NotesView
import org.spaceelephant.keepitapp.data.apiErrorMessage
import org.spaceelephant.keepitapp.data.offline.SyncStatus
import org.spaceelephant.keepitapp.ui.theme.KeepItColors

/**
 * The phone twin of the web HomePage: a drawer with Notes/Archive/Trash + the user's lists (with
 * counts), a topbar with search, and a single-column note list split into Pinned/Others in the
 * active view. Realtime keeps it live; pull-to-refresh (and the topbar refresh action) is a
 * manual resync.
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

    val notes by repo.notes.collectAsState()
    val lists by repo.lists.collectAsState()
    val filter by repo.filter.collectAsState()
    val loading by repo.loading.collectAsState()
    val isOnline by container.connectivity.isOnline.collectAsState()
    val pending by container.pendingChanges.collectAsState()
    val syncStatus by container.syncEngine.status.collectAsState()

    var search by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Changes that permanently failed to sync (e.g. the note was deleted elsewhere) surface once.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        container.syncEngine.syncErrors.collect { snackbarHostState.showSnackbar(it) }
    }

    // List management (online-only): the dialog being shown, if any.
    var newListOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ListDto?>(null) }
    var deleteTarget by remember { mutableStateOf<ListDto?>(null) }

    fun runListAction(fallback: String, block: suspend () -> Result<Unit>) {
        scope.launch {
            block().onFailure { snackbarHostState.showSnackbar(apiErrorMessage(it, fallback)) }
        }
    }

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = KeepItColors.Surface) {
                Text(
                    text = "keepIT",
                    color = KeepItColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Notes") },
                    selected = filter.view == NotesView.ACTIVE && filter.listIds.isEmpty(),
                    onClick = { applyFilter(NotesFilter(NotesView.ACTIVE)) },
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
                NavigationDrawerItem(
                    label = { Text("Notifications") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenNotifications()
                    },
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
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
                            IconButton(onClick = { scope.launch { repo.refreshAll() } }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = KeepItColors.TextMuted)
                            }
                            IconButton(onClick = { scope.launch { container.session.logout() } }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = "Sign out",
                                    tint = KeepItColors.TextMuted,
                                )
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
                    SyncStatusStrip(isOnline = isOnline, pending = pending, syncStatus = syncStatus)
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onCompose,
                    containerColor = KeepItColors.Accent,
                    contentColor = androidx.compose.ui.graphics.Color.Black,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            },
        ) { padding ->
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
                        color = KeepItColors.Accent,
                    )
                },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                when {
                    (loading || syncStatus == SyncStatus.SYNCING) && notes.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = KeepItColors.Accent,
                    )

                    // Empty states get a scrollable box so the pull gesture still works.
                    visible.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                q.isNotEmpty() -> "No notes match your search."
                                !isOnline && notes.isEmpty() -> "You're offline — your notes appear once you've connected."
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
                            items(pinned, key = { "p-${it.id}" }) { note ->
                                NoteCard(note = note, repo = repo, onOpen = { onOpenNote(note.id) })
                            }
                            item { SectionLabel("OTHERS") }
                        }
                        items(others, key = { it.id }) { note ->
                            NoteCard(note = note, repo = repo, onOpen = { onOpenNote(note.id) })
                        }
                    }
                }
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
                runListAction("Could not create the list.") { repo.createList(name) }
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
                runListAction("Could not rename the list.") { repo.renameList(target.id, name) }
            },
            onDismiss = { renameTarget = null },
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
                Button(onClick = {
                    deleteTarget = null
                    runListAction("Could not delete the list.") { repo.deleteList(target.id) }
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
            Button(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
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
 * One slim line under the top bar, shown only when something is worth saying: offline (with the
 * count of changes waiting), or an active replay. Silent whenever the app is online and in sync.
 */
@Composable
private fun SyncStatusStrip(isOnline: Boolean, pending: Int, syncStatus: SyncStatus) {
    val changes = if (pending == 1) "1 change" else "$pending changes"
    val text = when {
        syncStatus == SyncStatus.SYNCING && pending > 0 -> "Syncing $changes…"
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
            imageVector = if (!isOnline) Icons.Filled.CloudOff else Icons.Filled.CloudUpload,
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
    filter.listIds.isNotEmpty() -> "Filtered"
    else -> "keepIT"
}

private fun emptyCopy(view: NotesView): String = when (view) {
    NotesView.ACTIVE -> "Notes you add appear here."
    NotesView.ARCHIVED -> "No archived notes."
    NotesView.TRASHED -> "Trash is empty."
}

/** Client-side search over title, body, and checklist items — same rule as the web grid. */
private fun NoteDto.matchesSearch(q: String): Boolean =
    (title ?: "").lowercase().contains(q) ||
        (body ?: "").lowercase().contains(q) ||
        checklistItems.any { it.text.lowercase().contains(q) }
