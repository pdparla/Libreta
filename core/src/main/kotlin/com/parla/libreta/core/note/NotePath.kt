package com.parla.libreta.core.note

/**
 * Location of a note relative to the notes root, with `/` as separator (e.g. `Recetas/Pan.md`).
 *
 * Folders are the note's category, the same convention the Nextcloud Notes app uses. Only `.md` and `.txt`
 * files count as notes, and anything under a hidden segment is ignored: Nextcloud Notes keeps
 * attachments in `.attachments.<id>/` folders.
 */
data class NotePath(val value: String) : Comparable<NotePath> {

    init {
        require(isValid(value)) { "Not a valid note path: '$value'" }
    }

    val fileName: String get() = value.substringAfterLast('/')

    /** Folder path inside the notes root, empty for notes at the top level. */
    val category: String get() = value.substringBeforeLast('/', missingDelimiterValue = "")

    val baseName: String get() = fileName.substringBeforeLast('.')

    val extension: String get() = fileName.substringAfterLast('.')

    fun withBaseName(baseName: String): NotePath = of(category, baseName, extension)

    override fun compareTo(other: NotePath): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        val NOTE_EXTENSIONS: Set<String> = setOf("md", "txt")

        fun of(category: String, baseName: String, extension: String = "md"): NotePath =
            NotePath(if (category.isEmpty()) "$baseName.$extension" else "$category/$baseName.$extension")

        /** Returns the path if [raw] names a note, or null for other files, hidden entries and malformed paths. */
        fun parseOrNull(raw: String): NotePath? = if (isValid(raw)) NotePath(raw) else null

        private fun isValid(raw: String): Boolean {
            if (raw.isEmpty() || raw.any { it == '\\' || it.isISOControl() }) return false
            val segments = raw.split('/')
            // Rejects empty segments ("a//b", leading or trailing '/'), ".", ".." and hidden files or folders.
            if (segments.any { it.isBlank() || it.startsWith('.') }) return false
            val fileName = segments.last()
            val dot = fileName.lastIndexOf('.')
            return dot > 0 && fileName.substring(dot + 1).lowercase() in NOTE_EXTENSIONS
        }
    }
}
