package org.hyperstarit.keepitapp.data.portability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.hyperstarit.keepitapp.data.ListDto
import org.hyperstarit.keepitapp.data.NoteArchiveDto
import org.hyperstarit.keepitapp.data.NoteDto
import org.hyperstarit.keepitapp.data.NoteMediaDto
import org.hyperstarit.keepitapp.data.offline.PendingOp
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The keepIT export archive, as a file format: a zip holding `keepit-export.json`
 * ([NoteArchiveDto]) and the original bytes of every attached image under
 * `media/<noteId>/<mediaId>.<ext>`.
 *
 * Everything in this file is deliberately plain JVM — `java.util.zip`, [File], streams — with no
 * Android types anywhere, so the format is unit-testable without an emulator (the same reason
 * [org.hyperstarit.keepitapp.data.offline.MediaCache] takes a `File` root). Anything that needs a
 * `Uri`, a `ContentResolver` or an image decoder lives in [PortabilityRepository] instead.
 */
object Archive {

    /** The manifest's name at the root of the archive. */
    const val MANIFEST = "keepit-export.json"

    /**
     * How the manifest is read and written — one configuration, because a writer and a reader that
     * disagree produce an archive that only round-trips by luck. `encodeDefaults` in particular is
     * load-bearing: without it `schemaVersion` would be omitted whenever it equals its default,
     * which is always, and every archive this app wrote would arrive at an importer with no version
     * on it at all.
     *
     * Kept private so the encode/decode below are the only way in: callers never hold a serializer,
     * which is also what lets the minified smoke test exercise this without R8 keep rules for
     * kotlinx.serialization's own entry points.
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Serializes a manifest for writing into an archive. */
    fun encodeManifest(manifest: NoteArchiveDto): String = json.encodeToString(manifest)

    /** Parses a manifest, returning null when it cannot be read at all. */
    fun decodeManifest(text: String): NoteArchiveDto? =
        runCatching { json.decodeFromString<NoteArchiveDto>(text) }.getOrNull()

    /** Where one image sits inside the archive. */
    fun mediaPath(noteId: String, mediaId: String, extension: String): String =
        "media/$noteId/$mediaId$extension"

    /**
     * The (note id, media id) a media entry's path encodes, or null when the entry is not one.
     *
     * The ids are parsed out of the path and never used as a path themselves — an imported entry's
     * bytes are always rewritten under a name this device generates — so a hostile entry name has
     * nowhere to escape to.
     */
    fun parseMediaPath(entryName: String): Pair<String, String>? {
        val parts = entryName.split('/')
        val at = parts.lastIndexOf("media")
        if (at < 0 || parts.size - at != 3) return null
        val noteId = parts[at + 1].takeIf { it.isNotBlank() } ?: return null
        val mediaId = parts[at + 2].substringBeforeLast('.').takeIf { it.isNotBlank() } ?: return null
        return noteId to mediaId
    }

    /**
     * Finds the manifest, tolerating a wrapping folder — people re-zip a folder they unpacked and
     * the manifest ends up one level down. The shallowest match wins.
     */
    fun manifestEntry(zip: ZipFile): ZipEntry? =
        zip.entries().asSequence()
            .filter { it.name.substringAfterLast('/').equals(MANIFEST, ignoreCase = true) }
            .minByOrNull { it.name.count { c -> c == '/' } }
}

/** One image to write into an archive: the bytes, plus the ids that place them. */
data class ArchiveImage(
    val noteId: String,
    val mediaId: String,
    val extension: String,
    val file: File,
)

/** A manifest and the image files that belong with it — what [ArchiveWriter] needs to write one. */
data class ArchiveContent(
    val manifest: NoteArchiveDto,
    val images: List<ArchiveImage>,
)

/** Width, height and file extension of a staged image, as an image decoder reports them. */
data class ImageInfo(val width: Int, val height: Int, val extension: String)

/**
 * Builds the archive a **standalone** device exports.
 *
 * Standalone images are not on any note: `NoteDto.media` is empty and every attachment is still a
 * queued [PendingOp.AttachMedia] pointing at a staged file (see
 * [org.hyperstarit.keepitapp.data.offline.MediaStaging]). So the manifest is assembled here rather
 * than taken from the cache as-is — each note gains the [NoteMediaDto] rows its staged files
 * justify, and only those, because promising an image the archive doesn't carry is worse than
 * leaving it out.
 *
 * @param probe reads a staged file's dimensions and type; null when it cannot be read at all.
 */
fun buildStandaloneArchive(
    notes: List<NoteDto>,
    lists: List<ListDto>,
    ops: List<PendingOp>,
    appVersion: String,
    exportedAtUtc: String,
    probe: (File) -> ImageInfo?,
): ArchiveContent {
    val attachmentsByNote = ops
        .filterIsInstance<PendingOp.AttachMedia>()
        .groupBy { it.noteId }

    val images = mutableListOf<ArchiveImage>()

    val manifestNotes = notes.map { note ->
        val attachments = attachmentsByNote[note.id].orEmpty()
        if (attachments.isEmpty()) return@map note.copy(media = emptyList())

        val media = mutableListOf<NoteMediaDto>()
        attachments.forEachIndexed { index, op ->
            val file = File(op.stagedPath)
            val info = if (file.isFile) probe(file) else null
            if (info == null) return@forEachIndexed

            media += NoteMediaDto(
                id = op.tempMediaId,
                width = info.width,
                height = info.height,
                byteSize = file.length(),
                order = index,
                createdAtUtc = op.enqueuedAtUtc,
            )
            images += ArchiveImage(note.id, op.tempMediaId, info.extension, file)
        }
        note.copy(media = media)
    }

    return ArchiveContent(
        manifest = NoteArchiveDto(
            exportedAtUtc = exportedAtUtc,
            appVersion = appVersion,
            lists = lists,
            notes = manifestNotes,
        ),
        images = images,
    )
}

/** Writes an [ArchiveContent] as a zip. */
object ArchiveWriter {

    /**
     * Streams the archive into [out], which is closed by the caller.
     *
     * Images are stored without compression — they are already compressed, so deflating them again
     * costs time on a phone and saves nothing.
     *
     * @return how many images were written; one that vanished between listing and writing is
     *   skipped rather than failing the export.
     */
    fun write(out: OutputStream, content: ArchiveContent): Int {
        var written = 0
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(Archive.MANIFEST))
            zip.write(Archive.encodeManifest(content.manifest).toByteArray())
            zip.closeEntry()

            for (image in content.images) {
                if (!image.file.isFile) continue
                zip.setLevel(0)
                zip.putNextEntry(ZipEntry(Archive.mediaPath(image.noteId, image.mediaId, image.extension)))
                image.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                written++
            }
        }
        return written
    }
}

/**
 * An opened archive: its manifest, and the image entries it carries keyed by the ids in their path.
 * Holds the [ZipFile] open, so callers close it.
 */
class ReadArchive(
    private val zip: ZipFile,
    val manifest: NoteArchiveDto,
    private val media: Map<Pair<String, String>, ZipEntry>,
) : AutoCloseable {

    /** The declared uncompressed size of one image, or null when the archive doesn't carry it. */
    fun imageSize(noteId: String, mediaId: String): Long? = media[noteId to mediaId]?.size

    /** Opens one image's bytes, or null when the archive doesn't carry it. */
    fun openImage(noteId: String, mediaId: String): InputStream? =
        media[noteId to mediaId]?.let { zip.getInputStream(it) }

    override fun close() = zip.close()
}

/** Why an archive could not be opened — each maps to a message the user can act on. */
enum class ArchiveError {
    /** Not a zip at all, or a damaged one. */
    NOT_A_ZIP,

    /** A readable zip with no keepIT manifest in it — someone else's export. */
    NOT_A_KEEPIT_ARCHIVE,

    /** The manifest is present but unreadable. */
    UNREADABLE_MANIFEST,

    /** Written by a newer keepIT than this build knows how to read. */
    FROM_A_NEWER_VERSION,
}

/** The outcome of opening an archive: the archive, or why it could not be opened. */
sealed interface OpenArchiveResult {
    data class Opened(val archive: ReadArchive) : OpenArchiveResult
    data class Failed(val error: ArchiveError) : OpenArchiveResult
}

/** Opens and validates an archive file. */
object ArchiveReader {

    /**
     * Reads [file] as a keepIT archive.
     *
     * @param parseManifest decodes the manifest JSON, returning null when it cannot be read;
     *   the format's own parser unless a test substitutes one.
     */
    fun open(
        file: File,
        parseManifest: (String) -> NoteArchiveDto? = Archive::decodeManifest,
    ): OpenArchiveResult {
        val zip = runCatching { ZipFile(file) }.getOrNull()
            ?: return OpenArchiveResult.Failed(ArchiveError.NOT_A_ZIP)

        var keepOpen = false
        try {
            val entry = Archive.manifestEntry(zip)
                ?: return OpenArchiveResult.Failed(ArchiveError.NOT_A_KEEPIT_ARCHIVE)

            // Bounded before it is read: the guard against a small entry that expands into gigabytes.
            if (entry.size > MAX_MANIFEST_BYTES) {
                return OpenArchiveResult.Failed(ArchiveError.UNREADABLE_MANIFEST)
            }

            val json = runCatching { zip.getInputStream(entry).use { it.readBytes().decodeToString() } }
                .getOrNull()
                ?: return OpenArchiveResult.Failed(ArchiveError.UNREADABLE_MANIFEST)

            val manifest = parseManifest(json)
                ?: return OpenArchiveResult.Failed(ArchiveError.UNREADABLE_MANIFEST)

            // Forward compatibility runs one way: this build cannot know what a newer archive
            // means, and guessing would silently drop whatever was added to it.
            if (manifest.schemaVersion > NoteArchiveDto.CURRENT_SCHEMA_VERSION) {
                return OpenArchiveResult.Failed(ArchiveError.FROM_A_NEWER_VERSION)
            }

            val media = zip.entries().asSequence()
                .mapNotNull { e -> Archive.parseMediaPath(e.name)?.let { it to e } }
                .toMap()

            keepOpen = true
            return OpenArchiveResult.Opened(ReadArchive(zip, manifest, media))
        } finally {
            if (!keepOpen) runCatching { zip.close() }
        }
    }

    /**
     * Ceiling on the manifest's uncompressed size. A few hundred notes of text is a couple of
     * megabytes; well under this, and a phone has to decode it into memory.
     */
    const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
}
