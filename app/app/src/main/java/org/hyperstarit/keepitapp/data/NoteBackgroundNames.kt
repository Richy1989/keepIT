package org.hyperstarit.keepitapp.data

/**
 * The rules for note background names and files, shared with the server
 * (`keepITCore/Notes/NoteBackground.cs`) and the web (`features/notes/backgrounds.ts`), and kept
 * free of Android types so the JVM unit tests hold the shipped `backgrounds/` folder to them.
 *
 * A background is an image in the repository's `backgrounds/` folder, which this app ships as the
 * assets folder `backgrounds/` (see `app/build.gradle.kts`): `<name>.png` or `<name>.webp`, a
 * stencil whose alpha is the pattern, painted in the note colour's ink. The note stores only `<name>`.
 */
object NoteBackgroundNames {
    /** Lowercase letters, digits and single hyphens: the server's `NoteBackground.Pattern`. */
    private val NAME = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    /** A background file: its name and a format with alpha that every platform decodes. */
    private val FILE = Regex("^([^/.]+)\\.(png|webp)$")

    /** Whether [name] is a name the server accepts as a note's background. */
    fun isValid(name: String?): Boolean =
        name != null && name.length in 1..NoteLimits.BACKGROUND && NAME.matches(name)

    /** [name] when it [isValid], otherwise null — for an imported archive, which drops what it can't use. */
    fun validOrNull(name: String?): String? = name?.takeIf(::isValid)

    /** One background, and the file it is drawn from. */
    data class Entry(val name: String, val file: String) {
        /** For people: the name with its hyphens as spaces, capitalised ("Grid dots"). */
        val label: String get() = name.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * The backgrounds a folder's [files] make, sorted by name, with the files that make none: a
     * name the server would refuse, or a format without alpha or that not every platform decodes.
     */
    fun parse(files: List<String>): Pair<List<Entry>, List<String>> {
        val found = sortedMapOf<String, String>()
        val rejected = mutableListOf<String>()
        for (file in files) {
            val name = FILE.matchEntire(file)?.groupValues?.get(1)
            if (name == null || !isValid(name)) rejected += file else found[name] = file
        }
        return found.map { (name, file) -> Entry(name, file) } to rejected.sorted()
    }
}
