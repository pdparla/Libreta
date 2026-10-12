package com.parla.libreta.core.note

import java.text.Normalizer

/**
 * What a note can be found by (docs/11-busqueda.md): its title, its text (for a handwritten note, only what the user
 * typed, not the page images) and, for a handwritten note, what each page says.
 */
data class SearchableNote(val path: NotePath, val title: String, val text: String, val pages: List<String> = emptyList()) {
    // Normalized once, on the first search, not on every keystroke. In the body, so not part of equals/hashCode/copy.
    internal val normalizedTitle: String by lazy { NoteSearch.normalize(title) }
    internal val normalizedText: String by lazy { NoteSearch.normalize(text) }
    internal val normalizedPages: List<String> by lazy { pages.map { NoteSearch.normalize(it) } }
}

/** Where a query was found: in the title, the text, or page [page] (0-based) of the handwriting. */
data class SearchHit(val path: NotePath, val where: Where, val page: Int, val snippet: String) {
    enum class Where { TITLE, TEXT, PAGE }
}

/** Finds notes by what they say, ignoring case and accents. */
object NoteSearch {

    /** The notes matching [query], in the order given, each with where it matched first. */
    fun search(notes: List<SearchableNote>, query: String): List<SearchHit> {
        val needle = normalize(query.trim())
        if (needle.isEmpty()) return emptyList()
        return notes.mapNotNull { note -> hitIn(note, needle) }
    }

    private fun hitIn(note: SearchableNote, needle: String): SearchHit? {
        snippetOf(note.title, note.normalizedTitle, needle)?.let { return SearchHit(note.path, SearchHit.Where.TITLE, -1, it) }
        snippetOf(note.text, note.normalizedText, needle)?.let { return SearchHit(note.path, SearchHit.Where.TEXT, -1, it) }
        val normalizedPages = note.normalizedPages
        note.pages.forEachIndexed { i, page ->
            snippetOf(page, normalizedPages[i], needle)?.let { return SearchHit(note.path, SearchHit.Where.PAGE, i, it) }
        }
        return null
    }

    /**
     * About [SNIPPET] characters of [text] around the first match of [needle] in [normalized] (its [normalize]d form,
     * the same length), on one line; null if it is not there.
     */
    private fun snippetOf(text: String, normalized: String, needle: String): String? {
        val at = normalized.indexOf(needle)
        if (at < 0) return null
        // Widened to whole words when a word boundary is near, so a snippet does not start or end mid-word.
        var start = (at - SNIPPET / 2).coerceAtLeast(0)
        val earliest = (start - WORD_REACH).coerceAtLeast(0)
        var s = start
        while (s > earliest && !text[s - 1].isWhitespace()) s--
        if (s == 0 || text[s - 1].isWhitespace()) start = s
        var end = (at + needle.length + SNIPPET / 2).coerceAtMost(text.length)
        val latest = (end + WORD_REACH).coerceAtMost(text.length)
        var e = end
        while (e < latest && !text[e].isWhitespace()) e++
        if (e == text.length || text[e].isWhitespace()) end = e
        val piece = text.substring(start, end).replace(WHITESPACE, " ").trim()
        return (if (start > 0) "…" else "") + piece + (if (end < text.length) "…" else "")
    }

    /**
     * Lower case without accents, keeping one character per character so that positions in the result are positions
     * in the original: "Médico" and "medico" match.
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) out.append(if (c.code < 128) c.lowercaseChar() else folded(c))
        return out.toString()
    }

    /** What a non-ASCII [c] becomes: the base of its NFD form (é = e + ´) in lower case, worked out once per character. */
    private fun folded(c: Char): Char {
        val table = foldedTable
        var folded = table[c.code]
        if (folded == NOT_YET) {
            folded = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
            table[c.code] = folded
        }
        return folded
    }

    // Filled as characters turn up (a racing thread just works the same answer out twice). No NFD form starts with 0.
    private val foldedTable: CharArray by lazy { CharArray(Char.MAX_VALUE.code + 1) }
    private const val NOT_YET = '\u0000'
    private val WHITESPACE = Regex("""\s+""")

    private const val SNIPPET = 60
    private const val WORD_REACH = 15
}
