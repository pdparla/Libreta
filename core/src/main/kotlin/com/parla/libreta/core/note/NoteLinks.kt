package com.parla.libreta.core.note

/**
 * Links between notes written as `[[Title]]`, `[[Category/Title]]` or `[[Title|text shown]]` (docs/guide/notes.md).
 * A title cannot hold `|` or `/` ([NoteNaming]), so both are free to separate the parts. Links inside code
 * (`` `…` `` and fenced blocks) are text.
 */
object NoteLinks {

    /** One `[[…]]`: what it points to and the text to show. */
    data class Link(val target: String, val label: String)

    private val LINK = Regex("""\[\[([^\[\]|\n]+?)(?:\|([^\[\]\n]+?))?]]""")
    private val FENCE = Regex("""^\s{0,3}(```|~~~)""")

    /** The links in [markdown], in order. */
    fun of(markdown: String): List<Link> = buildList { outsideCode(markdown) { text -> addAll(linksIn(text).map { it.second }) } }

    /**
     * [markdown] with each link as a Markdown link to [scheme] plus its target (`[text](<wiki:Title>)`), for the
     * formatted view to draw and resolve. Code is left as it is.
     */
    fun toMarkdownLinks(markdown: String, scheme: String): String = buildString {
        outsideCode(markdown, onCode = ::append) { text ->
            var last = 0
            for ((range, link) in linksIn(text)) {
                append(text, last, range.first)
                append('[').append(link.label.replace("[", "\\[").replace("]", "\\]")).append("](<")
                append(scheme).append(link.target.replace("<", "").replace(">", "")).append(">)")
                last = range.last + 1
            }
            append(text, last, text.length)
        }
    }

    /**
     * The note [target] names among [notes]: by category and title when it has a `/`, otherwise by title, ignoring
     * case and a `.md` or `.txt` ending. Several with that title: the one in [from]'s category, else the first by
     * path. Null if none.
     */
    fun resolve(target: String, notes: Collection<NotePath>, from: NotePath? = null): NotePath? {
        val wanted = target.trim().removeSuffixIgnoringCase(".md").removeSuffixIgnoringCase(".txt")
        val category = NoteNaming.categoryName(wanted.substringBeforeLast('/', ""))
        val title = NoteNaming.fileBaseName(wanted.substringAfterLast('/'))
        val matches = notes.filter { path ->
            path.isNote && path.baseName.equals(title, ignoreCase = true) &&
                (!wanted.contains('/') || path.category.equals(category, ignoreCase = true))
        }.sorted()
        return matches.firstOrNull { it.category == from?.category } ?: matches.firstOrNull()
    }

    /** The notes among [texts] (path to Markdown) that link to [to], in path order, [to] itself left out. */
    fun backlinks(to: NotePath, texts: Map<NotePath, String>): List<NotePath> {
        val paths = texts.keys
        return texts.filter { (from, markdown) ->
            from != to && of(markdown).any { resolve(it.target, paths, from) == to }
        }.keys.sorted()
    }

    private fun linksIn(text: String): List<Pair<IntRange, Link>> = LINK.findAll(text).map { match ->
        val target = match.groupValues[1].trim()
        val label = match.groupValues[2].trim().ifEmpty { target }
        match.range to Link(target, label)
    }.filter { it.second.target.isNotEmpty() }.toList()

    /**
     * Calls [onText] with the parts of [markdown] outside code and [onCode] with the rest, in order, so that
     * together they give [markdown] back.
     */
    private fun outsideCode(markdown: String, onCode: (String) -> Unit = {}, onText: (String) -> Unit) {
        val text = StringBuilder()
        var fence: String? = null
        for (line in markdown.split('\n').let { lines -> lines.mapIndexed { i, l -> if (i < lines.size - 1) "$l\n" else l } }) {
            val marker = FENCE.find(line)?.groupValues?.get(1)
            when {
                fence != null -> {
                    onCode(line)
                    if (marker == fence) fence = null
                }
                marker != null -> {
                    flushInline(text, onCode, onText)
                    onCode(line)
                    fence = marker
                }
                else -> text.append(line)
            }
        }
        flushInline(text, onCode, onText)
    }

    /** [text] split at inline code spans (`` `…` ``), which go to [onCode]. */
    private fun flushInline(text: StringBuilder, onCode: (String) -> Unit, onText: (String) -> Unit) {
        var i = 0
        val s = text.toString()
        while (i < s.length) {
            val open = s.indexOf('`', i)
            if (open < 0) break
            val ticks = s.substring(open).takeWhile { it == '`' }
            val close = s.indexOf(ticks, open + ticks.length)
            if (close < 0) break
            if (open > i) onText(s.substring(i, open))
            onCode(s.substring(open, close + ticks.length))
            i = close + ticks.length
        }
        if (i < s.length) onText(s.substring(i))
        text.setLength(0)
    }

    private fun String.removeSuffixIgnoringCase(suffix: String) =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
