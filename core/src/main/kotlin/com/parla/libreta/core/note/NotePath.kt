package com.parla.libreta.core.note

/**
 * Location of a file in the notes tree, relative to its root, with `/` as separator. Two kinds of file are
 * synced, following Nextcloud Notes conventions:
 *
 * - **notes**: `.md` and `.txt` files; their folders are the note's category (`Recetas/Pan.md`);
 * - **attachments**: images, ink strokes, audio... directly inside an `.attachments.<id>/` folder next to the note
 *   that links to them (`Recetas/.attachments.k3f9a2/pagina-1.png`).
 *
 * Anything else, including every other hidden file or folder, is not part of the notes tree.
 */
data class NotePath(val value: String) : Comparable<NotePath> {

    enum class Kind { NOTE, ATTACHMENT }

    init {
        require(kindOf(value) != null) { "Not a valid note or attachment path: '$value'" }
    }

    val kind: Kind get() = kindOf(value)!!

    val isNote: Boolean get() = kind == Kind.NOTE

    val fileName: String get() = value.substringAfterLast('/')

    /** Folder path inside the notes root, empty at the top level. For a note, this is its category. */
    val category: String get() = value.substringBeforeLast('/', missingDelimiterValue = "")

    val baseName: String get() = fileName.substringBeforeLast('.')

    val extension: String get() = fileName.substringAfterLast('.')

    fun withBaseName(baseName: String): NotePath = of(category, baseName, extension)

    /** [relative] (as written in a Markdown link, e.g. `.attachments.x/a.png`) resolved against this file's folder. */
    fun resolveSibling(relative: String): NotePath? =
        parseOrNull(if (category.isEmpty()) relative else "$category/$relative")

    override fun compareTo(other: NotePath): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        val NOTE_EXTENSIONS: Set<String> = setOf("md", "txt")
        val ATTACHMENT_EXTENSIONS: Set<String> =
            setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "json", "m4a", "mp3", "ogg", "wav", "pdf")
        const val ATTACHMENTS_FOLDER_PREFIX = ".attachments."

        fun of(category: String, baseName: String, extension: String = "md"): NotePath =
            NotePath(if (category.isEmpty()) "$baseName.$extension" else "$category/$baseName.$extension")

        /** Returns the path if [raw] is a note or an attachment, or null for anything else. */
        fun parseOrNull(raw: String): NotePath? = if (kindOf(raw) != null) NotePath(raw) else null

        /** Whether [folder] (relative, no trailing slash) can contain synced files: a category or an attachments folder. */
        fun isSyncedFolder(folder: String): Boolean {
            if (folder.isEmpty()) return true
            val segments = folder.split('/')
            if (segments.any { !isPlainSegment(it) }) return false
            val hidden = segments.indices.filter { segments[it].startsWith('.') }
            return hidden.isEmpty() || (hidden == listOf(segments.lastIndex) && isAttachmentsFolder(segments.last()))
        }

        fun isAttachmentsFolder(name: String): Boolean =
            name.startsWith(ATTACHMENTS_FOLDER_PREFIX) && name.length > ATTACHMENTS_FOLDER_PREFIX.length

        private fun kindOf(raw: String): Kind? {
            if (raw.isEmpty() || raw.any { it == '\\' || it.isISOControl() }) return null
            val segments = raw.split('/')
            if (segments.any { !isPlainSegment(it) }) return null
            val fileName = segments.last()
            val dot = fileName.lastIndexOf('.')
            if (fileName.startsWith('.') || dot <= 0) return null
            val extension = fileName.substring(dot + 1).lowercase()
            val folders = segments.dropLast(1)
            if (folders.isNotEmpty() && isAttachmentsFolder(folders.last())) {
                if (folders.dropLast(1).any { it.startsWith('.') }) return null
                return if (extension in ATTACHMENT_EXTENSIONS) Kind.ATTACHMENT else null
            }
            if (folders.any { it.startsWith('.') }) return null
            return if (extension in NOTE_EXTENSIONS) Kind.NOTE else null
        }

        // Rejects empty segments ("a//b", leading or trailing '/'), blank ones, "." and "..".
        private fun isPlainSegment(segment: String) = segment.isNotBlank() && segment != "." && segment != ".."
    }
}
