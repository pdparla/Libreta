package com.parla.libreta.core.note

/**
 * Turns note titles and categories into file and folder names the same way Nextcloud Notes does: the title is the
 * first line of the note, the category is its folder, and both drop characters that are unsafe in file names.
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
    fun fileBaseName(title: String): String = clean(title).ifEmpty { DEFAULT_TITLE }

    /**
     * Normalises a category typed by the user. `/` separates sub-categories, as in Nextcloud Notes. Each level is
     * cleaned like a file name, and empty or hidden levels are dropped. Returns "" for no category.
     */
    fun categoryName(raw: String): String =
        raw.split('/').map(::clean).filter { it.isNotEmpty() }.joinToString("/")

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

    /** One safe path segment: no forbidden or control characters, single spaces, no leading dots; may be empty. */
    private fun clean(text: String): String =
        text.replace(FORBIDDEN_CHARS, "")
            .replace(WHITESPACE_RUN, " ")
            .filterNot { it.isISOControl() }
            .trim()
            .trimStart('.')
            .take(MAX_TITLE_LENGTH)
            .trim()
}
