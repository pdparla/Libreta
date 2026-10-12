package com.parla.libreta.core.ink

import com.parla.libreta.core.note.NotePath

/**
 * The files a handwritten note's state depends on besides its own Markdown, found from that Markdown without
 * reading anything: the places its strokes may be and the places its transcripts may be. Whoever caches something
 * derived from a note (the search index, the transcriber's record of what is read) keeps the versions of these files
 * next to the note's own, so a change in any of them, or a file appearing, invalidates it.
 */
object InkNoteFiles {

    /** The note itself, every strokes file it may use and every transcripts file it may have, deduplicated. */
    fun dependenciesOf(path: NotePath, markdown: String): List<NotePath> {
        val result = LinkedHashSet<NotePath>()
        result += path
        for (inkFile in InkNoteFormat.inkFileCandidates(markdown)) {
            path.resolveSibling(inkFile)?.let(result::add)
            val folder = InkNoteFormat.folderOf(inkFile) ?: continue
            path.resolveSibling("$folder/${InkTranscripts.FILE}")?.let(result::add)
        }
        return result.toList()
    }
}
