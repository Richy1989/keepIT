# Note Media — Android Implementation Plan (3 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Android app attach, view and remove note images — including **while offline**, with the upload replaying when connectivity returns.

**Architecture:** Two new `PendingOp` types carry attach and delete through the existing outbox. A picked image is copied into app-private staging *before* the op is queued, because a `content://` grant is revocable and the outbox outlives it. Pending attachments are projected from the outbox rather than written into the cache, so `Dtos.kt` stays an exact mirror of the C# DTOs. Downloaded bytes live in a file cache keyed by the immutable media id, and Coil renders from those files.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Retrofit + OkHttp + kotlinx.serialization, Coil 3, JUnit (JVM) + AndroidJUnit (instrumented), R8.

**Spec:** `docs/superpowers/specs/2026-09-16-note-media-design.md` — read §2, §7, §9 and §11 before starting.

**Depends on:** `2026-09-16-note-media-api.md` complete. The web plan is independent of this one.

**Branch:** `feat/note-media` (already created).

## Global Constraints

- **`data/Dtos.kt` is hand-synced to the C# DTOs and there is no codegen.** Field names and nullability must match `NoteMediaDto` exactly. Never add a client-only field to a DTO — that is the one place drift can start.
- **Go through `NotesRepository`.** UI never calls the API directly.
- **R8 is the thing that breaks release builds here.** Anything constructed reflectively — including by the framework, from a manifest entry — goes in `reflectivelyConstructed` in `app/app/build.gradle.kts`.
- **`proguard-rules-minified.pro` keeps the API surface the instrumented tests reference, never the internals they test.** If a smoke test needs a new keep, keep the narrowest entry point that links.
- **All commands run from `app/`** and use the Windows wrapper: `./gradlew.bat`.
- **Unit tests run as `:app:testMinifiedUnitTest`** — that is the only unit-test task (`testBuildType` scopes test components to the `minified` variant).
- **Match the heavy KDoc style of the surrounding files.**
- **The Glance widget stays text-only** (spec §1). Adding `media` to `NoteDto` is additive, so the widget's snapshot projection and its unit tests keep working untouched — do not extend the widget to render images, and if its tests fail, that is a regression to fix rather than a feature to add.
- **Commits** are prefixed `app:`.

## File Structure

**Create:**
- `data/offline/MediaStaging.kt` — copies a picked URI into app-private storage.
- `data/offline/MediaCache.kt` — downloaded bytes, LRU-capped, keyed by media id.
- `ui/notes/MediaRow.kt` — the editor's image row.
- `ui/notes/MediaViewer.kt` — full-screen image viewer.
- `app/src/test/java/org/hyperstarit/keepitapp/offline/MediaOpsTest.kt`
- `app/src/test/java/org/hyperstarit/keepitapp/offline/MediaCacheTest.kt`
- `app/src/androidTest/java/org/hyperstarit/keepitapp/smoke/MediaSmokeTest.kt`
- `app/src/main/res/xml/file_paths.xml` — FileProvider paths for camera capture.

**Modify:**
- `data/Dtos.kt` — `NoteMediaDto`, `NoteDto.media`.
- `data/KeepItApi.kt` — upload / download / delete.
- `data/offline/PendingOp.kt` — `AttachMedia`, `DeleteMedia`, `targetId`.
- `data/offline/Outbox.kt` — coalesce carve-out, `remapId`, dropped-op reporting.
- `data/offline/NoteOps.kt` — `applyOp` for delete, `pendingMedia` projection.
- `data/offline/SyncEngine.kt` — replay and failure messages.
- `data/NotesRepository.kt` — `attachMedia`, `removeMedia`, pending projection.
- `ui/notes/NoteCard.kt`, `ui/notes/EditorScreen.kt` — the C1 hero and the editor row.
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — Coil, keep-rules entry.
- `app/src/main/AndroidManifest.xml` — the FileProvider entry.

---

### Task 1: DTOs and API surface

**Files:**
- Modify: `app/src/main/java/org/hyperstarit/keepitapp/data/Dtos.kt:88-111`, `data/KeepItApi.kt`

**Interfaces:**
- Consumes: the API from plan 1.
- Produces: `NoteMediaDto(id, width, height, byteSize, order, createdAtUtc)`, `NoteDto.media: List<NoteMediaDto>`, and `KeepItApi.uploadNoteMedia / downloadNoteMedia / deleteNoteMedia`.

- [ ] **Step 1: Mirror the C# DTO**

In `Dtos.kt`, beside `ChecklistItemDto`:

```kotlin
/**
 * One image attached to a note — mirrors `NoteMediaDto` in the C# API (the source of truth).
 * Carries no URL: the path is built from the note and media ids and fetched as an authenticated
 * request. [width]/[height] let a card reserve its box before the bytes arrive.
 */
@Serializable
data class NoteMediaDto(
    val id: String,
    val width: Int = 0,
    val height: Int = 0,
    val byteSize: Long = 0,
    val order: Int = 0,
    val createdAtUtc: String = "",
)
```

Add to `NoteDto`, after `checklistItems`:

```kotlin
    val media: List<NoteMediaDto> = emptyList(),
```

- [ ] **Step 2: Add the endpoints**

In `KeepItApi.kt`, after the note endpoints:

```kotlin
    /** Attaches one image to a note (multipart, one file per request). */
    @Multipart
    @POST("api/notes/{noteId}/media")
    suspend fun uploadNoteMedia(
        @Path("noteId") noteId: String,
        @Part file: MultipartBody.Part,
    ): NoteMediaDto

    /** Streams one image; [size] is "thumb" or "full". */
    @Streaming
    @GET("api/notes/{noteId}/media/{mediaId}")
    suspend fun downloadNoteMedia(
        @Path("noteId") noteId: String,
        @Path("mediaId") mediaId: String,
        @Query("size") size: String,
    ): ResponseBody

    /** Removes one image from a note. */
    @DELETE("api/notes/{noteId}/media/{mediaId}")
    suspend fun deleteNoteMedia(
        @Path("noteId") noteId: String,
        @Path("mediaId") mediaId: String,
    )
```

Add the imports `okhttp3.MultipartBody`, `okhttp3.ResponseBody`, `retrofit2.http.Multipart`, `retrofit2.http.Part`, `retrofit2.http.Streaming`, `retrofit2.http.Query`.

- [ ] **Step 3: Compile**

```bash
cd app && ./gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/org/hyperstarit/keepitapp/data
git commit -m "app: add note media DTO and endpoints"
```

---

### Task 2: Outbox ops for media (TDD)

**Files:**
- Modify: `data/offline/PendingOp.kt`, `data/offline/Outbox.kt`, `data/offline/NoteOps.kt`
- Test: `app/src/test/java/org/hyperstarit/keepitapp/offline/MediaOpsTest.kt`

**Interfaces:**
- Consumes: `PendingOp`, `coalesce`, `applyOp`.
- Produces: `PendingOp.AttachMedia(noteId, stagedPath, tempMediaId, opId, enqueuedAtUtc)`, `PendingOp.DeleteMedia(noteId, mediaId, opId, enqueuedAtUtc)`, `fun pendingMedia(ops: List<PendingOp>, noteId: String): List<PendingOp.AttachMedia>`.

- [ ] **Step 1: Write the failing tests**

Create `MediaOpsTest.kt`:

```kotlin
package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.UpdateNoteDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import org.hyperstarit.keepitapp.data.offline.applyOp
import org.hyperstarit.keepitapp.data.offline.coalesce
import org.hyperstarit.keepitapp.data.offline.pendingMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline rules specific to image attachments. Media ops are the first outbox entries that
 * carry a file rather than a JSON payload, and the first that must survive coalescing untouched.
 */
class MediaOpsTest {

    private fun attach(noteId: String, temp: String = "t1") =
        PendingOp.AttachMedia(noteId = noteId, stagedPath = "/tmp/$temp.jpg", tempMediaId = temp)

    @Test
    fun `two attachments on one note both survive coalescing`() {
        val ops = coalesce(coalesce(emptyList(), attach("n1", "a")), attach("n1", "b"))

        assertEquals(2, ops.filterIsInstance<PendingOp.AttachMedia>().size)
    }

    @Test
    fun `an update does not swallow a queued attachment`() {
        val withAttach = coalesce(emptyList(), attach("n1"))
        val ops = coalesce(withAttach, PendingOp.Update("n1", UpdateNoteDto(title = "hi")))

        assertEquals(1, ops.filterIsInstance<PendingOp.AttachMedia>().size)
        assertEquals(1, ops.filterIsInstance<PendingOp.Update>().size)
    }

    @Test
    fun `an attachment does not replace an earlier update`() {
        val withUpdate = coalesce(emptyList(), PendingOp.Update("n1", UpdateNoteDto(title = "hi")))
        val ops = coalesce(withUpdate, attach("n1"))

        assertEquals(1, ops.filterIsInstance<PendingOp.Update>().size)
        assertEquals(1, ops.filterIsInstance<PendingOp.AttachMedia>().size)
    }

    @Test
    fun `deleting the note drops its queued media ops`() {
        val withAttach = coalesce(emptyList(), attach("n1"))
        val ops = coalesce(withAttach, PendingOp.Delete("n1"))

        assertTrue(ops.filterIsInstance<PendingOp.AttachMedia>().isEmpty())
    }

    @Test
    fun `deleting media removes it from the cached note`() {
        val note = NoteDto(id = "n1", media = listOf(NoteMediaDto(id = "m1"), NoteMediaDto(id = "m2")))

        val result = applyOp(listOf(note), PendingOp.DeleteMedia("n1", "m1"))

        assertEquals(listOf("m2"), result.single().media.map { it.id })
    }

    @Test
    fun `attaching does not alter the cached note`() {
        val note = NoteDto(id = "n1", media = listOf(NoteMediaDto(id = "m1")))

        val result = applyOp(listOf(note), attach("n1"))

        assertEquals(listOf("m1"), result.single().media.map { it.id })
    }

    @Test
    fun `pending attachments are projected per note`() {
        val ops = listOf(attach("n1", "a"), attach("n2", "b"), attach("n1", "c"))

        assertEquals(listOf("a", "c"), pendingMedia(ops, "n1").map { it.tempMediaId })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd app && ./gradlew.bat :app:testMinifiedUnitTest --tests "*MediaOpsTest*"
```
Expected: compilation failure — `PendingOp.AttachMedia`, `PendingOp.DeleteMedia`, `pendingMedia` and `NoteMediaDto` don't exist yet (the last one exists after Task 1; the rest don't).

- [ ] **Step 3: Add the ops**

In `PendingOp.kt`, after `Delete`:

```kotlin
    /**
     * Attaches an image to a note. Unlike every other op this one references a *file*: [stagedPath]
     * points at an app-private copy made when the user picked the image. A `content://` grant is
     * revocable and dies on reboot, while this op routinely outlives both, so the bytes must be ours
     * before the op is queued. The staged file is deleted once the upload lands or fails for good.
     */
    @Serializable
    @SerialName("attachMedia")
    data class AttachMedia(
        val noteId: String,
        val stagedPath: String,
        val tempMediaId: String,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()

    /** Removes an image from a note. */
    @Serializable
    @SerialName("deleteMedia")
    data class DeleteMedia(
        val noteId: String,
        val mediaId: String,
        override val opId: String = newOpId(),
        override val enqueuedAtUtc: String = "",
    ) : PendingOp()
```

Extend the `targetId` `when` with:

```kotlin
            is AttachMedia -> noteId
            is DeleteMedia -> noteId
```

- [ ] **Step 4: Add the coalesce carve-out**

In `coalesce`, add a branch to the `when (incoming)`:

```kotlin
        // Media ops never coalesce, from either side: two photos are two independent uploads, and
        // an Update must not swallow an attachment (they touch different resources). Note that a
        // note-level Delete still annihilates them below — that branch filters by targetId.
        is PendingOp.AttachMedia, is PendingOp.DeleteMedia -> ops + incoming
```

Update the KDoc block above `coalesce` with a matching bullet.

- [ ] **Step 5: Extend `remapId`**

In `Outbox.remapId`, add:

```kotlin
                is PendingOp.AttachMedia -> if (op.noteId == tempId) op.copy(noteId = realId) else op
                is PendingOp.DeleteMedia -> if (op.noteId == tempId) op.copy(noteId = realId) else op
```

This is the case that matters most: a photo attached to a note that was itself created offline would otherwise upload to a note id the server has never seen.

- [ ] **Step 6: Extend `applyOp` and add the projection**

In `NoteOps.kt`, add to `applyOp`'s `when`:

```kotlin
    // Attaching can't be represented on a NoteDto: there is no server id, width or height yet, and
    // inventing a client-only field on the DTO is exactly the drift the hand-sync rule forbids.
    // Pending attachments are projected separately by [pendingMedia] and rendered from their staged
    // file instead.
    is PendingOp.AttachMedia -> notes

    is PendingOp.DeleteMedia -> notes.map { n ->
        if (n.id != op.noteId) n else n.copy(media = n.media.filterNot { it.id == op.mediaId })
    }
```

And at the end of the file:

```kotlin
/** The still-queued attachments for one note, in the order they were picked. */
fun pendingMedia(ops: List<PendingOp>, noteId: String): List<PendingOp.AttachMedia> =
    ops.filterIsInstance<PendingOp.AttachMedia>().filter { it.noteId == noteId }
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
cd app && ./gradlew.bat :app:testMinifiedUnitTest --tests "*MediaOpsTest*"
```
Expected: all 7 pass.

- [ ] **Step 8: Run the whole unit suite**

```bash
cd app && ./gradlew.bat :app:testMinifiedUnitTest
```
Expected: everything still passes — the new `when` branches must not have changed existing coalescing.

- [ ] **Step 9: Commit**

```bash
git add app/app/src/main/java/org/hyperstarit/keepitapp/data/offline app/app/src/test
git commit -m "app: add attach/delete media outbox ops"
```

---

### Task 3: Staging picked images (TDD where it's pure)

**Files:**
- Create: `data/offline/MediaStaging.kt`
- Modify: `data/offline/Outbox.kt`, `data/NotesRepository.kt`

**Interfaces:**
- Consumes: `PendingOp.AttachMedia`, `Outbox`.
- Produces: `MediaStaging.stage(context: Context, uri: Uri, tempMediaId: String): File?`, `MediaStaging.delete(path: String)`; `Outbox.enqueue` now returns `List<PendingOp>` (the ops it dropped); `NotesRepository.attachMedia(context, noteId, uri): Boolean` and `removeMedia(noteId, mediaId)`; `Outbox.pendingOps: StateFlow<List<PendingOp>>`; `NotesRepository.pendingMediaFor(noteId): Flow<List<PendingOp.AttachMedia>>`.

- [ ] **Step 1: Make `enqueue` report what it dropped**

Coalescing can discard a queued `AttachMedia` (deleting the note annihilates its ops). Its staged file would then leak forever. Change `Outbox.enqueue`:

```kotlin
    /**
     * Merges an op into the queue and returns the ops coalescing discarded, so a caller can release
     * resources they owned — a dropped [PendingOp.AttachMedia] still has a staged file on disk.
     */
    suspend fun enqueue(op: PendingOp): List<PendingOp> = mutex.withLock {
        val before = ops
        ops = coalesce(ops, op).toMutableList()
        persist()
        before.filter { old -> ops.none { it.opId == old.opId } }
    }
```

Existing callers ignore the return value, so nothing else changes.

- [ ] **Step 2: Write the staging helper**

```kotlin
package org.hyperstarit.keepitapp.data.offline

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App-private copies of images the user picked, made *before* the attach op is queued.
 *
 * A picked `content://` URI is a temporary, revocable permission grant that does not survive a
 * reboot — and the outbox routinely does. Copying the bytes up front is what makes "attach a photo
 * with no signal, close the app, fly home, and have it upload" work at all.
 */
class MediaStaging(context: Context) {

    private val dir = File(context.filesDir, "offline/media-staging").apply { mkdirs() }

    /** Copies the content behind [uri] into staging, returning the file, or null if unreadable. */
    suspend fun stage(context: Context, uri: Uri, tempMediaId: String): File? =
        withContext(Dispatchers.IO) {
            val target = File(dir, "$tempMediaId.img")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                target
            }.getOrNull()
        }

    /** Best-effort delete of a staged file once its op has landed or failed for good. */
    fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}
```

- [ ] **Step 3: Add the repository methods**

In `NotesRepository`, beside the other mutations:

```kotlin
    /**
     * Attaches an image to a note. The bytes are staged locally first, so the attachment survives
     * the picker's permission grant, a reboot, and an arbitrarily long offline stretch.
     */
    suspend fun attachMedia(context: Context, noteId: String, uri: Uri): Boolean {
        val tempMediaId = java.util.UUID.randomUUID().toString()
        val staged = staging.stage(context, uri, tempMediaId) ?: return false
        mutate(
            PendingOp.AttachMedia(
                noteId = resolve(noteId),
                stagedPath = staged.absolutePath,
                tempMediaId = tempMediaId,
            ),
        )
        return true
    }

    /** Removes an image from a note. */
    suspend fun removeMedia(noteId: String, mediaId: String) =
        mutate(PendingOp.DeleteMedia(resolve(noteId), mediaId))
```

In `mutate`, release staged files for anything coalescing dropped:

```kotlin
        val dropped = outbox.enqueue(op)
        dropped.filterIsInstance<PendingOp.AttachMedia>().forEach { staging.delete(it.stagedPath) }
```

Add a `staging: MediaStaging` constructor parameter and wire it where `NotesRepository` is built.

- [ ] **Step 4: Expose pending attachments to the UI**

Add a flow the editor can observe:

```kotlin
    /**
     * The attachments still queued for a note, so the editor can show a picked image immediately —
     * projected from the outbox rather than stored on [NoteDto], which must stay an exact mirror of
     * the server DTO.
     */
    fun pendingMediaFor(noteId: String): Flow<List<PendingOp.AttachMedia>> =
        outbox.pendingOps.map { ops -> pendingMedia(ops, resolve(noteId)) }
```

This needs `Outbox` to expose the queue as a flow. Add beside `pendingCount`:

```kotlin
    private val _pendingOps = MutableStateFlow<List<PendingOp>>(emptyList())

    /** The queue as a flow, for UI that renders queued work (attachments in the editor).*/
    val pendingOps: StateFlow<List<PendingOp>> = _pendingOps
```

and set `_pendingOps.value = ops.toList()` inside `persist()` alongside `_pendingCount.value = ops.size`.

- [ ] **Step 5: Compile**

```bash
cd app && ./gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run the unit suite**

```bash
cd app && ./gradlew.bat :app:testMinifiedUnitTest
```
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add app/app/src/main/java/org/hyperstarit/keepitapp/data
git commit -m "app: stage picked images and queue attach ops"
```

---

### Task 4: Replaying media ops

**Files:**
- Modify: `data/offline/SyncEngine.kt`

**Interfaces:**
- Consumes: `KeepItApi.uploadNoteMedia`, `KeepItApi.deleteNoteMedia`, `MediaStaging.delete`.
- Produces: no new surface.

- [ ] **Step 1: Handle the ops in `replay`**

Add to the `when (op)` in `SyncEngine.replay`:

```kotlin
                    is PendingOp.AttachMedia -> {
                        val file = File(op.stagedPath)
                        if (!file.exists()) {
                            // Nothing to send — treat as done rather than retrying forever.
                            outbox.removeFirst(op.opId)
                            continue
                        }
                        val body = file.asRequestBody("image/*".toMediaType())
                        client.api.uploadNoteMedia(
                            op.noteId,
                            MultipartBody.Part.createFormData("file", "image.jpg", body),
                        )
                        staging.delete(op.stagedPath)
                    }

                    is PendingOp.DeleteMedia -> client.api.deleteNoteMedia(op.noteId, op.mediaId)
```

Add a `staging: MediaStaging` constructor parameter to `SyncEngine` and the imports `okhttp3.MediaType.Companion.toMediaType`, `okhttp3.MultipartBody`, `okhttp3.RequestBody.Companion.asRequestBody`, `java.io.File`.

- [ ] **Step 2: Release staged files on permanent failure**

In the `4xx` branch that drops an op, before `outbox.removeFirst(op.opId)`:

```kotlin
                        if (op is PendingOp.AttachMedia) staging.delete(op.stagedPath)
```

Without this, every rejected image (too large, wrong type, note gone) leaves its bytes in staging forever.

- [ ] **Step 3: Give the failures readable messages**

In `permanentFailureMessage`, add to the `what` `when`:

```kotlin
            is PendingOp.AttachMedia -> "an image"
            is PendingOp.DeleteMedia -> "removing an image"
```

and extend the `why` `when` so the server's own limits are explained rather than generically refused:

```kotlin
            409 -> "the note already has the maximum number of images"
            413 -> "the image is too large (max 10 MB)"
            400 -> "the file isn't a supported image"
```

- [ ] **Step 4: Compile and test**

```bash
cd app && ./gradlew.bat :app:compileDebugKotlin && ./gradlew.bat :app:testMinifiedUnitTest
```
Expected: both succeed.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/org/hyperstarit/keepitapp/data/offline/SyncEngine.kt
git commit -m "app: replay media ops from the outbox"
```

---

### Task 5: The media cache (TDD)

**Files:**
- Create: `data/offline/MediaCache.kt`
- Test: `app/src/test/java/org/hyperstarit/keepitapp/offline/MediaCacheTest.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Interfaces:**
- Consumes: `KeepItApi.downloadNoteMedia`.
- Produces: `MediaCache(root: File, download: suspend (String, String, String) -> ResponseBody?)` with `suspend fun file(noteId, mediaId, size): File?` and `fun evict(maxBytes: Long)`.

**Design note:** the cache takes a plain `File` root rather than a `Context`, which is what makes it testable on the JVM. Immutable media ids mean there is no invalidation logic — only eviction.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.hyperstarit.keepitapp.offline

import org.hyperstarit.keepitapp.data.offline.MediaCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Eviction rules for the on-disk image cache. Media ids are immutable, so there is nothing to
 *  invalidate — only a size cap to honour. */
class MediaCacheTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `evict keeps newest files within the cap`() {
        val root = temp.newFolder("media")
        val cache = MediaCache(root) { _, _, _ -> null }

        // 3 files of 100 bytes, oldest first.
        listOf("a", "b", "c").forEachIndexed { i, name ->
            java.io.File(root, "$name.img").apply {
                writeBytes(ByteArray(100))
                setLastModified(1_000L + i * 1_000L)
            }
        }

        cache.evict(maxBytes = 250)

        val remaining = root.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("b.img", "c.img"), remaining)
    }

    @Test
    fun `evict is a no-op under the cap`() {
        val root = temp.newFolder("media")
        val cache = MediaCache(root) { _, _, _ -> null }
        java.io.File(root, "a.img").writeBytes(ByteArray(10))

        cache.evict(maxBytes = 1_000)

        assertTrue(java.io.File(root, "a.img").exists())
    }

    @Test
    fun `a cached file is returned without downloading`() = kotlinx.coroutines.runBlocking {
        val root = temp.newFolder("media")
        var downloads = 0
        val cache = MediaCache(root) { _, _, _ -> downloads++; null }
        java.io.File(root, "n1_m1_thumb.img").writeBytes(ByteArray(5))

        val file = cache.file("n1", "m1", "thumb")

        assertEquals(0, downloads)
        assertTrue(file != null && file.exists())
        assertFalse(file!!.length() == 0L)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd app && ./gradlew.bat :app:testMinifiedUnitTest --tests "*MediaCacheTest*"
```
Expected: compilation failure — `MediaCache` doesn't exist.

- [ ] **Step 3: Implement the cache**

```kotlin
package org.hyperstarit.keepitapp.data.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File

/**
 * Downloaded note images on disk. Bytes are fetched through the app's own authenticated client and
 * then handed to the image loader as plain files, which keeps auth out of the rendering path.
 *
 * A media id's bytes never change, so this cache has no invalidation logic at all — only a size cap.
 * Takes a [File] root rather than a Context so the eviction rules are unit-testable on the JVM.
 */
class MediaCache(
    private val root: File,
    private val download: suspend (noteId: String, mediaId: String, size: String) -> ResponseBody?,
) {
    init {
        root.mkdirs()
    }

    /** The cached file for one rendition, downloading it first when missing. Null when offline. */
    suspend fun file(noteId: String, mediaId: String, size: String): File? = withContext(Dispatchers.IO) {
        val target = File(root, "${noteId}_${mediaId}_$size.img")
        if (target.exists() && target.length() > 0) return@withContext target

        val body = runCatching { download(noteId, mediaId, size) }.getOrNull() ?: return@withContext null

        runCatching {
            // Write to a temp file and rename, so a half-written download is never served.
            val tmp = File(root, "${target.name}.tmp")
            body.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            tmp.renameTo(target)
            target
        }.getOrNull()
    }

    /** Drops least-recently-modified files until the cache fits in [maxBytes]. */
    fun evict(maxBytes: Long) {
        val files = root.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var kept = 0L
        for (file in files) {
            kept += file.length()
            if (kept > maxBytes) file.delete()
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
cd app && ./gradlew.bat :app:testMinifiedUnitTest --tests "*MediaCacheTest*"
```
Expected: all 3 pass.

- [ ] **Step 5: Add Coil**

In `gradle/libs.versions.toml`, add a `coil` version (use the current Coil 3 release — check `https://github.com/coil-kt/coil/releases` and pin the exact number; do not use `+`) and libraries:

```toml
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
```

In `app/build.gradle.kts` dependencies:

```kotlin
    implementation(libs.coil.compose)
```

Coil ships its own consumer ProGuard rules, so no keep entry is needed for it.

- [ ] **Step 6: Wire the cache into the repository**

Construct `MediaCache(File(context.filesDir, "offline/media")) { noteId, mediaId, size -> client.api.downloadNoteMedia(noteId, mediaId, size) }` where the repository is built and expose it as a property.

Then, after a successful full sync, prefetch thumbnails and evict. Prefetching is what makes the grid work offline at all — without it a user who goes offline sees empty boxes where every photo should be:

```kotlin
    /**
     * Warms the thumbnail cache for the notes in the grid, then trims it. Best-effort and bounded:
     * a failure here must never surface as a sync error, and only thumbnails are prefetched —
     * full-size images are fetched on demand when a note is opened.
     */
    private suspend fun prefetchThumbnails(notes: List<NoteDto>) {
        notes.asSequence()
            .filter { !it.isTrashed }
            .flatMap { note -> note.media.asSequence().map { note.id to it.id } }
            .take(MAX_PREFETCH)
            .forEach { (noteId, mediaId) ->
                runCatching { mediaCache.file(noteId, mediaId, "thumb") }
            }

        mediaCache.evict(MAX_MEDIA_CACHE_BYTES)
    }
```

with, alongside the other constants in the file:

```kotlin
private const val MAX_PREFETCH = 200
private const val MAX_MEDIA_CACHE_BYTES = 256L * 1024 * 1024
```

Call it at the end of the repository's `onFetched` handler.

- [ ] **Step 7: Compile and test**

```bash
cd app && ./gradlew.bat :app:compileDebugKotlin && ./gradlew.bat :app:testMinifiedUnitTest
```
Expected: both succeed.

- [ ] **Step 8: Commit**

```bash
git add app/app/src/main/java/org/hyperstarit/keepitapp/data app/app/src/test app/gradle/libs.versions.toml app/app/build.gradle.kts
git commit -m "app: add on-disk media cache and Coil"
```

---

### Task 6: Compose UI

**Files:**
- Create: `ui/notes/MediaRow.kt`, `ui/notes/MediaViewer.kt`
- Modify: `ui/notes/NoteCard.kt`, `ui/notes/EditorScreen.kt`

**Interfaces:**
- Consumes: `MediaCache`, `pendingMediaFor`, `NotesRepository.attachMedia/removeMedia`.
- Produces: `@Composable NoteMediaImage(noteId, mediaId, size, modifier)`, `@Composable MediaRow(...)`, `@Composable MediaViewer(...)`.

- [ ] **Step 1: The image composable**

In `MediaRow.kt`:

```kotlin
/**
 * One note image, resolved through [MediaCache] and rendered by Coil from the resulting file. The
 * cache does the authenticated fetch, so nothing here needs a token or a custom Coil fetcher.
 */
@Composable
fun NoteMediaImage(
    noteId: String,
    mediaId: String,
    size: String,
    modifier: Modifier = Modifier,
    cache: MediaCache = LocalMediaCache.current,
) {
    var file by remember(noteId, mediaId, size) { mutableStateOf<File?>(null) }

    LaunchedEffect(noteId, mediaId, size) {
        file = cache.file(noteId, mediaId, size)
    }

    val current = file
    if (current != null) {
        AsyncImage(
            model = current,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        // Offline and uncached, or still loading — a neutral block, never a broken-image glyph.
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
```

Provide `LocalMediaCache` as a `staticCompositionLocalOf<MediaCache>` supplied in `AppRoot`, matching however the repository is already provided there.

- [ ] **Step 2: The C1 hero on the card**

In `NoteCard.kt`, above the title/body content:

```kotlin
        if (note.media.isNotEmpty()) {
            Box {
                val first = note.media.first()
                val ratio = if (first.width > 0) first.width.toFloat() / first.height else 1f
                NoteMediaImage(
                    noteId = note.id,
                    mediaId = first.id,
                    size = "thumb",
                    modifier = Modifier
                        .fillMaxWidth()
                        // Reserve the real ratio, but never let one tall photo eat the card.
                        .aspectRatio(ratio.coerceAtLeast(4f / 3f)),
                )
                if (note.media.size > 1) {
                    Text(
                        text = "+${note.media.size - 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                note.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                ),
                            )
                            .padding(horizontal = 12.dp, top = 24.dp, bottom = 8.dp),
                    )
                }
            }
        }
```

Suppress the card's normal title when `note.media.isNotEmpty() && !note.title.isNullOrBlank()`, so it isn't rendered twice. Body and checklist rows stay below, unchanged — that is the whole point of C1 over C2.

- [ ] **Step 3: The editor row**

```kotlin
/**
 * The editor's image row: stored images plus anything still queued. A pending attachment renders
 * straight from its staged file, so a photo picked with no signal appears instantly and keeps
 * showing after a restart — the staged copy is ours, not a borrowed content:// grant.
 */
@Composable
fun MediaRow(
    noteId: String,
    media: List<NoteMediaDto>,
    pending: List<PendingOp.AttachMedia>,
    canEdit: Boolean,
    onRemove: (String) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media.isEmpty() && pending.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        itemsIndexed(media, key = { _, m -> m.id }) { index, m ->
            Box {
                NoteMediaImage(
                    noteId = noteId,
                    mediaId = m.id,
                    size = "thumb",
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpen(index) },
                )
                if (canEdit) {
                    IconButton(
                        onClick = { onRemove(m.id) },
                        modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove image",
                            tint = Color.White,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                .padding(4.dp),
                        )
                    }
                }
            }
        }

        items(pending, key = { it.tempMediaId }) { op ->
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = File(op.stagedPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(0.5f),
                )
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
```

Collect the pending list in `EditorScreen` with
`val pending by repo.pendingMediaFor(noteId).collectAsState(initial = emptyList())`, and pass
`onRemove = { scope.launch { repo.removeMedia(noteId, it) } }`.

- [ ] **Step 4: Picker and camera**

In `EditorScreen.kt`, register two launchers:

```kotlin
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { uris -> uris.forEach { scope.launch { repo.attachMedia(context, noteId, it) } } }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) captureUri?.let { scope.launch { repo.attachMedia(context, noteId, it) } }
    }
```

`PickVisualMedia` needs no runtime permission. **Do not declare `CAMERA` in the manifest** — `ACTION_IMAGE_CAPTURE` via `TakePicture` doesn't require it, and declaring it would force a permission prompt for nothing.

`captureUri` comes from `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg"))`.

- [ ] **Step 5: Manifest and file paths**

`res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="captures" path="." />
</paths>
```

In `AndroidManifest.xml`, inside `<application>`:

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 6: Compile**

```bash
cd app && ./gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Verify on a device (debug build)**

```bash
cd app && ./gradlew.bat :app:installDebug
```
- Attach from the gallery: the image appears in the editor immediately, then resolves to the server thumbnail.
- The card shows the full-bleed hero with the title on the scrim and `+N` when there are several.
- Take a photo with the camera button: it attaches.
- **Airplane mode:** attach two photos, force-stop the app, reopen → both still show as pending. Re-enable the network → both upload and the note updates.
- Attach to a note *created* while offline, then go online → note and images both land (this exercises `remapId`).
- Open a note shared with you as **Viewer**: images render, no remove buttons.

- [ ] **Step 8: Commit**

```bash
git add app/app/src/main
git commit -m "app: show and attach note images"
```

---

### Task 7: R8 guard and smoke test

**Files:**
- Modify: `app/build.gradle.kts` (`reflectivelyConstructed`), `proguard-rules-minified.pro`
- Create: `app/src/androidTest/java/org/hyperstarit/keepitapp/smoke/MediaSmokeTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: a guarded release build.

**Why this task exists:** `FileProvider` is named in the manifest and instantiated *by the framework, from that string*. R8 resolves reachability statically, sees no caller, keeps the class name and quietly drops the constructor. Nothing fails at build time; the camera button just silently does nothing in release. That is the exact failure that shipped a dead widget twice.

- [ ] **Step 1: Add the keep-rules entry**

In the `reflectivelyConstructed` map in `app/build.gradle.kts`:

```kotlin
    "androidx.core.content.FileProvider" to
        "named in the manifest and built by the framework from that string - losing its constructor silently breaks camera capture in release only",
```

- [ ] **Step 2: Write the smoke test**

```kotlin
package org.hyperstarit.keepitapp.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds the reflectively-constructed media types off the real (minified) dex. A unit test can't
 * see this: the class only ever comes into being through a name the framework resolves at runtime.
 */
@RunWith(AndroidJUnit4::class)
class MediaSmokeTest {

    @Test
    fun fileProvider_canBeConstructedReflectively() {
        val loader = InstrumentationRegistry.getInstrumentation().targetContext.classLoader
        val type = Class.forName("androidx.core.content.FileProvider", true, loader)

        assertNotNull(type.getDeclaredConstructor().newInstance())
    }
}
```

- [ ] **Step 3: Build the release APK, which runs the guard**

```bash
cd app && ./gradlew.bat :app:assembleRelease
```
Expected: BUILD SUCCESSFUL, including the `verifyReleaseKeepRules` task that `assembleRelease` is `finalizedBy`. If it fails naming `FileProvider`, add the narrowest keep rule that restores the constructor — not a blanket `-keep class androidx.**`.

- [ ] **Step 4: Run the instrumented suite**

With a device or emulator attached:
```bash
cd app && ./gradlew.bat :app:connectedMinifiedAndroidTest
```
Expected: all pass, including `VariantSanityTest` (which fails if the suite is ever pointed at an unminified build).

- [ ] **Step 5: Commit**

```bash
git add app/app/build.gradle.kts app/app/src/androidTest
git commit -m "app: guard FileProvider against R8 stripping"
```

---

### Task 8: Full verification

**Files:** none — this task only runs things.

- [ ] **Step 1: Unit tests**

```bash
cd app && ./gradlew.bat :app:testMinifiedUnitTest
```
Expected: all pass.

- [ ] **Step 2: Release build + keep-rule guard**

```bash
cd app && ./gradlew.bat :app:assembleRelease
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Instrumented smoke tests**

```bash
cd app && ./gradlew.bat :app:connectedMinifiedAndroidTest
```
Expected: all pass.

- [ ] **Step 4: Install the minified build and re-run the offline scenario**

```bash
cd app && ./gradlew.bat :app:installMinified
```
Release's R8 config with debug signing. Repeat the airplane-mode attach → reconnect → upload flow, and take one photo with the camera. **This is the build where a stripped `FileProvider` would show up**, so the camera path must be exercised here specifically, not just on debug.

- [ ] **Step 5: Commit any fixes**

```bash
git commit -am "app: fix issues found in minified verification"
```

---

## Done when

- `:app:testMinifiedUnitTest`, `:app:assembleRelease` (with `verifyReleaseKeepRules`) and `:app:connectedMinifiedAndroidTest` all pass.
- Attaching offline, force-stopping, and reconnecting uploads the image.
- Attaching to a note created offline works (temp-id remap).
- The camera path works **on the minified build**.
- A Viewer can see images but not add or remove them.
- `data/Dtos.kt` matches the C# `NoteMediaDto` field for field.
