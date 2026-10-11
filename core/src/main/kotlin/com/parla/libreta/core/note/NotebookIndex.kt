package com.parla.libreta.core.note

import com.parla.libreta.core.ink.InkNoteFormat
import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.ink.InkTranscriptService

/**
 * What every note can be found by, for the note list's search and the AI's tools (docs/11-busqueda.md,
 * docs/12-preguntar.md), so that both find exactly the same: the title, the text (for a handwritten note, what the
 * user typed, not the managed lines) and what each handwritten page says.
 *
 * @param exists whether a file is there, without reading it: telling a handwritten note apart must not read its
 *   strokes.
 */
class NotebookIndex(
    private val notes: NoteRepository,
    private val inkNotes: InkNoteService,
    private val transcripts: InkTranscriptService,
    private val exists: (NotePath) -> Boolean,
) {

    data class Entry(val searchable: SearchableNote, val isInk: Boolean) {
        val path: NotePath get() = searchable.path
    }

    /** The note at [path], whose Markdown is [markdown]. */
    fun entry(path: NotePath, markdown: String): Entry {
        val isInk = inkNotes.isInkNote(path, markdown, exists)
        val searchable = if (isInk) {
            SearchableNote(path, path.baseName, InkNoteFormat.userText(markdown), transcripts.readFor(path, markdown).map { it.text })
        } else {
            SearchableNote(path, path.baseName, markdown)
        }
        return Entry(searchable, isInk)
    }

    /** Every note, in no particular order. */
    fun all(): List<Entry> = notes.list().map { it.path }.filter { it.isNote }.mapNotNull { path ->
        notes.readOrNull(path)?.text?.let { entry(path, it) }
    }
}
