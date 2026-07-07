package org.spaceelephant.keepitapp.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.spaceelephant.keepitapp.ui.markdown.stripMarkdown
import org.spaceelephant.keepitapp.widget.KeepItWidget
import retrofit2.HttpException

/** Which slice of notes the grid shows — mirrors the web's `NotesView`. */
enum class NotesView { ACTIVE, ARCHIVED, TRASHED }

/** The grid's current filter: a view plus an optional set of list ids (union). */
data class NotesFilter(
    val view: NotesView = NotesView.ACTIVE,
    val listIds: Set<String> = emptySet(),
)

/**
 * A trimmed note snapshot the widget can render without auth or network. For checklist notes
 * [checklist] holds each item as its own display line (so the widget stacks them); for text notes
 * [preview] holds a one-line body excerpt and [checklist] is empty.
 */
@Serializable
data class WidgetNote(
    val id: String,
    val title: String,
    val preview: String,
    val color: String?,
    val checklist: List<String> = emptyList(),
)

/**
 * App-scoped server state for notes and lists (the TanStack-Query analogue): screens collect the
 * [notes]/[lists] flows; mutations call the API and then refetch, and SignalR pushes trigger the
 * same refetch for changes made on other devices. Also maintains the home-screen widget's cache of
 * recent notes after every active-grid load.
 */
class NotesRepository(
    private val client: ApiClient,
    private val appContext: Context,
) {
    private val widgetPrefs = appContext.getSharedPreferences("keepit_widget", Context.MODE_PRIVATE)

    val filter = MutableStateFlow(NotesFilter())

    private val _notes = MutableStateFlow<List<NoteDto>>(emptyList())
    val notes: StateFlow<List<NoteDto>> = _notes

    private val _lists = MutableStateFlow<List<ListDto>>(emptyList())
    val lists: StateFlow<List<ListDto>> = _lists

    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Wired by the app container: fires when a request 401s irrecoverably (session expired). */
    var onUnauthorized: (() -> Unit)? = null

    suspend fun refreshAll() {
        refreshNotes()
        refreshLists()
    }

    /** Reloads the grid for the current filter. Server order: pinned first, then last-updated. */
    suspend fun refreshNotes() {
        val f = filter.value
        loading.value = true
        runCatching {
            client.api.notes(
                archived = f.view == NotesView.ARCHIVED,
                trashed = f.view == NotesView.TRASHED,
                listIds = f.listIds.toList().ifEmpty { null },
            )
        }.onSuccess { fetched ->
            _notes.value = fetched
            error.value = null
            // The widget mirrors the default active grid (not archived/trash/list-filtered views).
            if (f.view == NotesView.ACTIVE && f.listIds.isEmpty()) updateWidget(fetched)
        }.onFailure(::handleFailure)
        loading.value = false
    }

    suspend fun refreshLists() {
        runCatching { client.api.lists() }
            .onSuccess { _lists.value = it }
            .onFailure(::handleFailure)
    }

    suspend fun setFilter(newFilter: NotesFilter) {
        filter.value = newFilter
        refreshNotes()
    }

    fun noteById(id: String): NoteDto? = _notes.value.find { it.id == id }

    /** Fetches one note directly (widget deep links can target a note not in the current grid). */
    suspend fun fetchNote(id: String): NoteDto? =
        runCatching { client.api.note(id) }.onFailure(::handleFailure).getOrNull()

    suspend fun create(dto: CreateNoteDto): NoteDto? =
        runCatching { client.api.createNote(dto) }
            .onSuccess { refreshAll() }
            .onFailure(::handleFailure)
            .getOrNull()

    suspend fun update(id: String, dto: UpdateNoteDto): NoteDto? =
        runCatching { client.api.updateNote(id, dto) }
            .onSuccess { refreshNotes() }
            .onFailure(::handleFailure)
            .getOrNull()

    suspend fun setState(id: String, state: NoteStateDto) {
        runCatching { client.api.setNoteState(id, state) }
            .onSuccess { refreshAll() }
            .onFailure(::handleFailure)
    }

    suspend fun setLists(id: String, listIds: List<String>) {
        runCatching { client.api.setNoteLists(id, SetNoteListsDto(listIds)) }
            .onSuccess { refreshAll() }
            .onFailure(::handleFailure)
    }

    suspend fun delete(id: String) {
        runCatching { client.api.deleteNote(id) }
            .onSuccess { refreshAll() }
            .onFailure(::handleFailure)
    }

    private fun handleFailure(t: Throwable) {
        if (t is HttpException && t.code() == 401) {
            onUnauthorized?.invoke()
            return
        }
        error.value = apiErrorMessage(t, "Couldn't reach the server.")
    }

    // ---- widget cache ----

    /** Writes the top notes to the widget's local cache and asks Glance to re-render. */
    private suspend fun updateWidget(notes: List<NoteDto>) {
        val top = notes.take(WIDGET_NOTE_COUNT).map { n ->
            val isChecklist = n.type == NoteTypes.CHECKLIST
            WidgetNote(
                id = n.id,
                title = n.title?.takeIf { it.isNotBlank() } ?: "",
                // The Glance widget renders plain strings, so Markdown syntax is stripped here.
                preview = if (isChecklist) "" else stripMarkdown(n.body ?: "").replace('\n', ' ').take(100),
                color = n.color,
                checklist = if (isChecklist) {
                    n.checklistItems.sortedBy { it.order }.take(WIDGET_CHECKLIST_LINES).map {
                        (if (it.isChecked) "☑ " else "☐ ") + it.text
                    }
                } else {
                    emptyList()
                },
            )
        }
        widgetPrefs.edit().putString(WIDGET_KEY, Json.encodeToString(top)).apply()
        runCatching { KeepItWidget().updateAll(appContext) }
    }

    companion object {
        private const val WIDGET_KEY = "notes_json"
        private const val WIDGET_NOTE_COUNT = 6
        private const val WIDGET_CHECKLIST_LINES = 4
        private val WidgetJson = Json { ignoreUnknownKeys = true }

        /** Read side for the Glance widget (sync, no network, works while signed out). */
        fun readWidgetNotes(context: Context): List<WidgetNote> {
            val raw = context.getSharedPreferences("keepit_widget", Context.MODE_PRIVATE)
                .getString(WIDGET_KEY, null) ?: return emptyList()
            return runCatching {
                WidgetJson.decodeFromString<List<WidgetNote>>(raw)
            }.getOrDefault(emptyList())
        }
    }
}
