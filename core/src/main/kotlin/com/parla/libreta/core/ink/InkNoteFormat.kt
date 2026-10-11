package com.parla.libreta.core.ink

import com.parla.libreta.core.note.NoteAttachments
import com.parla.libreta.core.note.NotePath

/**
 * How a handwritten note looks as Markdown, so that Nextcloud Notes shows its pages:
 *
 * ```markdown
 * # Nota a mano 2026-10-11 18:45
 *
 * (any text: typed later, or recognised from the handwriting)
 *
 * ![Página 1](.attachments.k3f9a2/pagina-1.png)
 *
 * [libreta-ink]: .attachments.k3f9a2/libreta.ink.json
 * ```
 *
 * The last line is a Markdown *link reference definition*: renderers do not display it, and it tells the tablet
 * that this note has strokes and where they are. Nextcloud Notes' editor drops it when it saves the note, as a
 * definition nothing uses, so a note also counts as handwritten when a folder its images live in has strokes; the
 * line comes back the next time the tablet saves. Only the page images and that line are managed here; every
 * other line belongs to the user and is kept as it is.
 */
object InkNoteFormat {

    const val INK_FILE = "libreta.ink.json"
    private const val REFERENCE_LABEL = "libreta-ink"

    private val INK_REFERENCE = Regex("""^\[$REFERENCE_LABEL]:\s*(\S+)\s*$""", RegexOption.MULTILINE)

    fun pageImageName(index: Int) = "pagina-${index + 1}.png"

    /**
     * Where the note's strokes file may be, relative to the note (e.g. `.attachments.k3/libreta.ink.json`), most
     * likely first: the one its reference line names, then one in each attachments folder it links to. Empty for
     * a note with no attachments at all. Which one exists, if any, only the repository knows.
     */
    fun inkFileCandidates(markdown: String): List<String> {
        val referenced = INK_REFERENCE.find(markdown)?.groupValues?.get(1)?.takeIf { folderOf(it) != null }
        val inLinkedFolders = NoteAttachments.foldersReferencedBy(markdown).map { "$it/$INK_FILE" }
        return (listOfNotNull(referenced) + inLinkedFolders).distinct()
    }

    /** The attachments folder holding [inkFile], e.g. `.attachments.k3`; null for a path outside one. */
    fun folderOf(inkFile: String): String? =
        inkFile.substringBeforeLast('/', "").takeIf { NotePath.isAttachmentsFolder(it) }

    /** Markdown for a new note titled [title] with [pageCount] pages in [folder]. */
    fun compose(title: String, folder: String, pageCount: Int): String =
        update("# $title\n", folder, pageCount)

    /**
     * [markdown] with its managed lines replaced: the user's lines stay in place, then one image per page and the
     * strokes reference at the end.
     */
    fun update(markdown: String, folder: String, pageCount: Int): String {
        val userLines = userLines(markdown, folder)
        return buildString {
            userLines.forEach { append(it).append('\n') }
            for (i in 0 until pageCount) {
                append('\n').append("![Página ${i + 1}]($folder/${pageImageName(i)})").append('\n')
            }
            append('\n').append("[$REFERENCE_LABEL]: $folder/$INK_FILE").append('\n')
        }
    }

    /**
     * [markdown] with [text] added after the user's lines, above the page images, separated by a blank line; for
     * text recognised from the handwriting.
     */
    fun withText(markdown: String, folder: String, pageCount: Int, text: String): String {
        val added = text.trim()
        if (added.isEmpty()) return update(markdown, folder, pageCount)
        val userText = userLines(markdown, folder).joinToString("\n")
        val combined = if (userText.isBlank()) added else "$userText\n\n$added"
        return update(combined, folder, pageCount)
    }

    /** What the user typed in a handwritten note's Markdown: without the page images and the strokes reference, of any folder. */
    fun userText(markdown: String): String {
        val anyPageImage = Regex("""^!\[[^\]]*]\(\.attachments\.[^/)]+/pagina-\d+\.png\)\s*$""")
        return markdown.lines().filterNot { anyPageImage.matches(it) || INK_REFERENCE.matches(it) }.joinToString("\n").trim()
    }

    /** The lines that belong to the user: everything but the page images and the strokes reference, trailing blanks dropped. */
    private fun userLines(markdown: String, folder: String): List<String> {
        val pageImage = Regex("""^!\[[^\]]*]\(${Regex.escape(folder)}/pagina-\d+\.png\)\s*$""")
        return markdown.lines()
            .filterNot { pageImage.matches(it) || INK_REFERENCE.matches(it) }
            .dropLastWhile { it.isBlank() }
    }
}
