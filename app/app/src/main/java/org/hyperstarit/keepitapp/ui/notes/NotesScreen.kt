package org.hyperstarit.keepitapp.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NotesFilter
import org.hyperstarit.keepitapp.data.NotesView
import org.hyperstarit.keepitapp.data.SessionState
import org.hyperstarit.keepitapp.data.UserDto
import org.hyperstarit.keepitapp.data.offline.SyncStatus
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/**
 * The phone twin of the web HomePage: a drawer with Notes/Archive/Trash + the user's lists (with
 * counts), a topbar with search, and a single-column note list split into Pinned/Others in the
 * active view. Realtime keeps it live; pull-to-refresh (and the topbar refresh action) is a
 * manual resync. The drawer ends in the signed-in account and Sign out, pinned below its
 * scrolling nav.
 *
 * In standalone mode there is nothing to sync with or sign out of: refresh, sign-out, the sync
 * strip and the server inbox all go, and the drawer says where the notes live.
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
    val standalone by container.appMode.standalone.collectAsState()
    val pendingMedia by repo.pendingMediaByNote.collectAsState()
    val session by container.session.state.collectAsState()
    // Set once Sign out is tapped, so a second tap can't start a second sign-out.
    var signingOut by remember { mutableStateOf(false) }

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
            // Given the drawer state, the sheet handles Back itself: an open drawer closes (following
            // a predictive back gesture) instead of Back falling through and finishing the activity.
            ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = KeepItColors.Surface) {
                // The nav scrolls on its own so a long run of lists never pushes the account
                // footer off the bottom of the sheet.
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "keepIT",
                        color = KeepItColors.Accent,
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
                        onSignOut = {
                            if (!signingOut) {
                                signingOut = true
                                scope.launch { container.session.logout() }
                            }
                        },
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
                        SyncStatusStrip(isOnline = isOnline, pending = pending, syncStatus = syncStatus)
                    }
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
            val content: @Composable BoxScope.() -> Unit = {
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
                                !standalone && !isOnline && notes.isEmpty() ->
                                    "You're offline — your notes appear once you've connected."
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
                                NoteCard(
                                    note = note,
                                    repo = repo,
                                    pendingMedia = pendingMedia[note.id].orEmpty(),
                                    onOpen = { onOpenNote(note.id) },
                                )
                            }
                            item { SectionLabel("OTHERS") }
                        }
                        items(others, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                repo = repo,
                                pendingMedia = pendingMedia[note.id].orEmpty(),
                                onOpen = { onOpenNote(note.id) },
                            )
                        }
                        // Below the last note, as on the web, rather than in the top bar: that is
                        // full already, and emptying the trash is rare enough to earn a scroll.
                        // Hidden while searching, where "all" would be ambiguous.
                        if (filter.view == NotesView.TRASHED && q.isEmpty()) {
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
                            color = KeepItColors.Accent,
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
        val sharedWithMe = target.count { !it.isOwner }
        AlertDialog(
            onDismissRequest = { emptyTrashTarget = null },
            containerColor = KeepItColors.Surface,
            title = {
                Text(if (target.size == 1) "Delete the note forever?" else "Delete all ${target.size} notes forever?")
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
                    onClick = {
                        emptyTrashTarget = null
                        scope.launch { repo.emptyTrash(target.map { it.id }) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(onClick = { emptyTrashTarget = null }) {
                    Text("Cancel", color = KeepItColors.TextMuted)
                }
            },
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
 * The foot of the drawer, below the scrolling nav: who is signed in — the web account menu's
 * header, with the same initial-letter avatar — and Sign out. Sign out is inset and tinted with
 * the error colour so it reads as an action, not one more place to go. [user] is null only in
 * the moment the session is changing; the account line waits for it, Sign out doesn't.
 */
@Composable
private fun DrawerAccountFooter(user: UserDto?, onSignOut: () -> Unit) {
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

/** Client-side search over title, body, and checklist items — same rule as the web grid. */
private fun NoteDto.matchesSearch(q: String): Boolean =
    (title ?: "").lowercase().contains(q) ||
        (body ?: "").lowercase().contains(q) ||
        checklistItems.any { it.text.lowercase().contains(q) }
