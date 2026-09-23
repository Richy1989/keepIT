package org.hyperstarit.keepitapp.data.portability

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.hyperstarit.keepitapp.data.ApiClient
import org.hyperstarit.keepitapp.data.AppMode
import org.hyperstarit.keepitapp.data.CreateNoteDto
import org.hyperstarit.keepitapp.data.ImportResultDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteStateDto
import org.hyperstarit.keepitapp.data.NotesRepository
import org.hyperstarit.keepitapp.data.ReminderRecurrences
import org.hyperstarit.keepitapp.data.SetNoteReminderDto
import org.hyperstarit.keepitapp.data.offline.Outbox
import org.hyperstarit.keepitapp.data.offline.epochMsOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How an export ended. */
sealed interface ExportOutcome {
    /** Written to the file the user picked, [bytes] long, carrying [notes] notes and [images] images. */
    data class Done(val bytes: Long, val notes: Int, val images: Int) : ExportOutcome
    data class Failed(val message: String) : ExportOutcome
}

/** How an import ended. */
sealed interface ImportOutcome {
    data class Done(val result: ImportResultDto) : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
}

/**
 * Export and import for the Android client — the same archive the server reads and writes.
 *
 * There are two paths through every operation, and which one runs is the only thing standalone
 * mode changes:
 *
 * * **Server-backed.** Export is `GET /api/export` streamed straight into the file the user picked,
 *   and import is a multipart POST. The server holds the authoritative copy, including images this
 *   device may never have downloaded, so asking it is both simpler and more complete than
 *   assembling an archive from a partial local cache.
 * * **Standalone.** There is no server, so the archive is built from and applied to the local cache
 *   and outbox. This is the half that matters most: a standalone device's notes exist nowhere else,
 *   and until now the settings screen said exactly that with nothing to offer about it.
 *
 * A standalone import creates notes through [NotesRepository] like any other edit, so every
 * imported note and image lands in the outbox too — connect a server later and the whole restored
 * set uploads into the account, which is the promise standalone mode already makes.
 */
class PortabilityRepository(
    private val context: Context,
    private val client: ApiClient,
    private val notesRepo: NotesRepository,
    private val outbox: Outbox,
    private val appMode: AppMode,
    private val appVersion: String,
) {

    /** The name offered in the save dialog. */
    fun suggestedFileName(): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        return "keepit-export-$day.zip"
    }

    // ---- export ----

    /** Writes an archive of this account (or this device) to [target]. */
    suspend fun export(target: Uri): ExportOutcome = withContext(Dispatchers.IO) {
        runCatching {
            if (appMode.isStandalone) exportLocally(target) else exportFromServer(target)
        }.getOrElse { ExportOutcome.Failed(failureMessage(it, "Your notes couldn't be exported.")) }
    }

    /** Server-backed: the server builds the archive, this just saves the stream. */
    private suspend fun exportFromServer(target: Uri): ExportOutcome {
        val body = client.api.export()
        var bytes = 0L
        body.byteStream().use { input ->
            openOutput(target)?.use { output -> bytes = input.copyTo(output) }
                ?: return ExportOutcome.Failed("That file couldn't be written.")
        }
        // The server's manifest is authoritative but not parsed back here; the counts shown are
        // what this device knows, which is what the user is looking at anyway.
        val notes = notesRepo.allNotes.value.count { it.isOwner }
        return ExportOutcome.Done(bytes, notes, notesRepo.allNotes.value.sumOf { it.media.size })
    }

    /** Standalone: build the archive from the cache and the staged images in the outbox. */
    private suspend fun exportLocally(target: Uri): ExportOutcome {
        val content = buildStandaloneArchive(
            notes = notesRepo.allNotes.value,
            lists = notesRepo.lists.value,
            ops = outbox.snapshot(),
            appVersion = appVersion,
            exportedAtUtc = nowUtc(),
            probe = ::probeImage,
        )

        var images = 0
        var bytes = 0L
        val output = openOutput(target) ?: return ExportOutcome.Failed("That file couldn't be written.")
        CountingOutputStream(output).use { counting ->
            images = ArchiveWriter.write(counting, content)
            bytes = counting.count
        }
        return ExportOutcome.Done(bytes, content.manifest.notes.size, images)
    }

    // ---- import ----

    /** Reads an archive from [source] and adds its contents to this account (or this device). */
    suspend fun import(source: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val spool = File(context.cacheDir, "import-${System.currentTimeMillis()}.zip")
        try {
            val copied = runCatching {
                context.contentResolver.openInputStream(source)?.use { input ->
                    spool.outputStream().use { output -> input.copyTo(output) }
                }
            }.getOrNull()
            if (copied == null || !spool.isFile || spool.length() == 0L) {
                return@withContext ImportOutcome.Failed("That file couldn't be read.")
            }

            runCatching {
                if (appMode.isStandalone) importLocally(spool) else importToServer(spool)
            }.getOrElse { ImportOutcome.Failed(failureMessage(it, "That archive couldn't be imported.")) }
        } finally {
            spool.delete()
        }
    }

    /** Server-backed: the server applies the archive, then this device resyncs from it. */
    private suspend fun importToServer(spool: File): ImportOutcome {
        // Checked here so an obviously wrong file is answered instantly, and with the same words,
        // rather than after uploading it. The server validates it again regardless.
        when (val opened = ArchiveReader.open(spool)) {
            is OpenArchiveResult.Failed -> return ImportOutcome.Failed(messageFor(opened.error))
            is OpenArchiveResult.Opened -> opened.archive.close()
        }

        val result = client.api.importArchive(
            MultipartBody.Part.createFormData(
                "file",
                "keepit-export.zip",
                spool.asRequestBody("application/zip".toMediaType()),
            ),
        )
        notesRepo.refreshAll()
        return ImportOutcome.Done(result)
    }

    /**
     * Standalone: replay the archive as ordinary local edits.
     *
     * Every note becomes a new note with a new id, exactly as the server's import does — nothing
     * already on the device is touched, so importing twice duplicates rather than overwrites. The
     * ops this queues are also what uploads the restored set if a server is connected later.
     */
    private suspend fun importLocally(spool: File): ImportOutcome {
        val opened = ArchiveReader.open(spool)
        if (opened is OpenArchiveResult.Failed) return ImportOutcome.Failed(messageFor(opened.error))
        val archive = (opened as OpenArchiveResult.Opened).archive

        archive.use {
            val warnings = mutableListOf<String>()
            var listsCreated = 0
            var listsReused = 0
            var imagesImported = 0
            var imagesSkipped = 0

            // A list whose name this device already uses is filed into, not cloned — repeated
            // restores would otherwise fill the drawer with copies.
            val existing = notesRepo.lists.value.associateBy { it.name.trim().lowercase() }
            val listIdByArchiveId = mutableMapOf<String, String>()
            for (list in archive.manifest.lists) {
                val name = list.name.trim()
                if (name.isEmpty()) continue
                val match = existing[name.lowercase()]
                if (match != null) {
                    listIdByArchiveId[list.id] = match.id
                    listsReused++
                } else {
                    listIdByArchiveId[list.id] = notesRepo.createList(name, list.color)
                    listsCreated++
                }
            }

            for (source in archive.manifest.notes) {
                val created = notesRepo.create(
                    CreateNoteDto(
                        type = source.type,
                        title = source.title,
                        body = source.body,
                        color = source.color,
                        checklistItems = source.checklistItems.ifEmpty { null },
                        listIds = source.listIds.mapNotNull(listIdByArchiveId::get).ifEmpty { null },
                    ),
                )

                if (source.isPinned || source.isArchived || source.isTrashed) {
                    notesRepo.setState(
                        created.id,
                        NoteStateDto(
                            isPinned = source.isPinned.takeIf { it },
                            isArchived = source.isArchived.takeIf { it },
                            isTrashed = source.isTrashed.takeIf { it },
                        ),
                    )
                }

                restoreReminder(source, created.id, warnings)

                for (media in source.media) {
                    val bytes = archive.openImage(source.id, media.id)
                    if (bytes == null) {
                        imagesSkipped++
                        warnings += "${label(source)}: an image listed in the archive was missing from it."
                        continue
                    }
                    val staged = File(context.cacheDir, "import-img-${media.id}")
                    val ok = runCatching {
                        bytes.use { input -> staged.outputStream().use { out -> input.copyTo(out) } }
                        notesRepo.attachMedia(context, created.id, Uri.fromFile(staged))
                    }.getOrDefault(false)
                    staged.delete()

                    if (ok) imagesImported++ else {
                        imagesSkipped++
                        warnings += "${label(source)}: an image couldn't be read."
                    }
                }
            }

            return ImportOutcome.Done(
                ImportResultDto(
                    notesImported = archive.manifest.notes.size,
                    listsCreated = listsCreated,
                    listsReused = listsReused,
                    imagesImported = imagesImported,
                    imagesSkipped = imagesSkipped,
                    warnings = warnings,
                ),
            )
        }
    }

    /**
     * Restores a note's reminder, **skipping a one-time one whose moment has already passed**.
     *
     * The server marks such a reminder already fired on import. This device cannot: a queued
     * reminder op always clears the fired flag, and the standalone scheduler would then treat every
     * overdue reminder in the archive as due right now — restoring a year-old backup would fire a
     * notification for each of them at once. Skipping is the same outcome the server reaches, minus
     * the past-reminder chip, and the user is told.
     */
    private suspend fun restoreReminder(source: NoteDto, noteId: String, warnings: MutableList<String>) {
        val remindAt = source.remindAtUtc ?: return
        val recurrence = source.reminderRecurrence ?: ReminderRecurrences.NONE
        val due = epochMsOrNull(remindAt) ?: return

        if (recurrence == ReminderRecurrences.NONE && (source.reminderFired || due <= System.currentTimeMillis())) {
            warnings += "${label(source)}: a reminder that had already passed was not restored."
            return
        }
        notesRepo.setReminder(noteId, SetNoteReminderDto(remindAt, recurrence))
    }

    // ---- helpers ----

    private fun label(note: NoteDto): String =
        note.title?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "An untitled note"

    private fun openOutput(target: Uri) = context.contentResolver.openOutputStream(target)

    /** Reads a staged image's dimensions and type without decoding its pixels. */
    private fun probeImage(file: File): ImageInfo? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ImageInfo(options.outWidth, options.outHeight, extensionFor(options.outMimeType))
    }

    /**
     * The archive entry's extension. Only cosmetic for keepIT — an importer identifies an image by
     * its content, not its name — but it decides whether the images are openable when someone
     * unzips the archive themselves, which is half the point of having one.
     */
    private fun extensionFor(mimeType: String?): String = when (mimeType) {
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        else -> ".jpg"
    }

    private fun messageFor(error: ArchiveError): String = when (error) {
        ArchiveError.NOT_A_ZIP -> "That file isn't a readable zip archive."
        ArchiveError.NOT_A_KEEPIT_ARCHIVE ->
            "That doesn't look like a keepIT export. Exports from other notes apps aren't supported yet."
        ArchiveError.UNREADABLE_MANIFEST -> "That archive's contents couldn't be read."
        ArchiveError.FROM_A_NEWER_VERSION ->
            "That archive was written by a newer version of keepIT. Update the app and try again."
    }

    private fun failureMessage(cause: Throwable, fallback: String): String =
        cause.message?.takeIf { it.isNotBlank() && it.length < 160 } ?: fallback

    private fun nowUtc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}

/** Counts what passes through, so an export can report its size without buffering it. */
private class CountingOutputStream(private val inner: java.io.OutputStream) : java.io.OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        inner.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        inner.write(b, off, len)
        count += len
    }

    override fun flush() = inner.flush()
    override fun close() = inner.close()
}
