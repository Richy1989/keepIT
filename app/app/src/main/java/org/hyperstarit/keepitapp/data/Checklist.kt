package org.hyperstarit.keepitapp.data

/**
 * Display order for a checklist: **unchecked rows first, checked rows at the bottom**, each group
 * keeping its stored order.
 *
 * The stored `order` is the row's *home* position — it changes only when rows are added, removed or
 * dragged, never when a box is ticked (the editor's save renumbers `order` from the home list, and
 * the server renumbers `Order` from the incoming array index). So ticking a row sinks it, unticking
 * puts it back in exactly the slot it came from, and a freshly added row lands at the end of the
 * unchecked block — all derived from persisted state, which is why it holds after a reload and
 * matches the web client, which applies the same rule in `web/src/features/notes/checklist.ts`.
 *
 * `sortedWith` is stable (TimSort), so the `order` tiebreak only matters for the rows the server
 * hasn't renumbered yet (an optimistic note still sitting in the outbox).
 *
 * Every read-only checklist surface goes through this: the note card preview, the widget preview.
 * The editor holds `EditableItem`s rather than DTOs and applies the same partition over its home
 * list — see `displayRows` in `ui/notes/EditorScreen.kt`.
 */
fun List<ChecklistItemDto>.inDisplayOrder(): List<ChecklistItemDto> =
    sortedWith(compareBy({ it.isChecked }, { it.order }))
