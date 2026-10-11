package com.parla.libreta.core.note

/**
 * Turns note titles into file names the same way Nextcloud Notes does: the title is the first line of the
 * note, and the file is named after it without characters that are unsafe in file names.
 */
object NoteNaming {

    const val DEFAULT_TITLE = "Nota nueva"
    const val MAX_TITLE_LENGTH = 100

    private val FORBIDDEN_CHARS = Regex("""[*|/\\:"<>?]""")
    private val MARKDOWN_PREFIX = Regex("""^(#+|[*+-])(\s+|$)""")
    private val WHITESPACE_RUN = Regex("""\s+""")

    /** First non-blank line of [content] without Markdown heading or list markers, or [DEFAULT_TITLE]. */
    fun titleOf(content: String): String {
        val firstLine = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return DEFAULT_TITLE
        return firstLine.replace(MARKDOWN_PREFIX, "").trim().ifEmpty { DEFAULT_TITLE }
    }

    /** File name (without extension) for [title], at most [MAX_TITLE_LENGTH] characters and never empty. */
    fun fileBaseName(title: String): String {
        val cleaned = title
            .replace(FORBIDDEN_CHARS, "")
            .replace(WHITESPACE_RUN, " ")
            .filterNot { it.isISOControl() }
            .trim()
            .trimStart('.')
        return cleaned.take(MAX_TITLE_LENGTH).trim().ifEmpty { DEFAULT_TITLE }
    }

    /** Path for a note titled [title] in [category], adding ` (2)`, ` (3)`... while [isTaken] says the name is in use. */
    fun uniquePath(
        title: String,
        category: String,
        isTaken: (NotePath) -> Boolean,
        extension: String = "md",
    ): NotePath {
        val baseName = fileBaseName(title)
        return generateSequence(1) { it + 1 }
            .map { n -> NotePath.of(category, if (n == 1) baseName else "$baseName ($n)", extension) }
            .first { !isTaken(it) }
    }
}
