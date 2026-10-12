package com.parla.libreta.core.note

import com.parla.libreta.core.ink.InkNoteFiles
import com.parla.libreta.core.ink.InkNoteFormat
import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.ink.InkTranscriptService
import java.util.concurrent.ConcurrentHashMap

/**
 * What every note can be found by, for the note list's search and the AI's tools (docs/11-busqueda.md,
 * docs/12-preguntar.md), so that both find exactly the same: the title, the text (for a handwritten note, what the
 * user typed, not the managed lines) and what each handwritten page says.
 *
 * [entries] and [all] keep what they built for each note, with the versions of the files it was built from (the
 * note, its possible strokes and transcripts files), and read a note again only when one of them changed. Safe to
 * use from several threads.
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

    /** [tags] are the ones in the note's Markdown ([NoteTags]). */
    data class Entry(val searchable: SearchableNote, val isInk: Boolean, val tags: List<String> = emptyList()) {
        val path: NotePath get() = searchable.path
    }

    /** An entry and the versions (null: no such file) of the files it was built from. */
    private class Cached(val entry: Entry, val dependencies: Map<NotePath, Version?>)

    private val cache = ConcurrentHashMap<NotePath, Cached>()

    /** The note at [path], whose Markdown is [markdown]. Always built from [markdown]: it is not cached. */
    fun entry(path: NotePath, markdown: String): Entry = build(path, markdown, exists)

    private fun build(path: NotePath, markdown: String, exists: (NotePath) -> Boolean): Entry {
        val isInk = inkNotes.isInkNote(path, markdown, exists)
        val searchable = if (isInk) {
            SearchableNote(path, path.baseName, InkNoteFormat.userText(markdown), transcripts.readFor(path, markdown).map { it.text })
        } else {
            SearchableNote(path, path.baseName, markdown)
        }
        return Entry(searchable, isInk, NoteTags.of(markdown))
    }

    /**
     * An entry for every note in [listing] (the result of [NoteRepository.list], attachments included), in no
     * particular order. Notes whose files have the same versions as when their entry was built are not read; the
     * entries of notes no longer listed are dropped. A note that disappears before it is read is left out.
     */
    fun entries(listing: List<NoteEntry>): List<Entry> {
        val versions = listing.associate { it.path to it.version }
        val paths = versions.keys.filter { it.isNote }
        (cache as MutableMap<NotePath, Cached>).keys.retainAll(paths.toSet())
        return paths.mapNotNull { path ->
            val cached = cache[path]
            if (cached != null && cached.dependencies.all { (file, version) -> versions[file] == version }) {
                return@mapNotNull cached.entry
            }
            // Versions come from the listing, taken before the reads: if a file changes meanwhile, the entry is
            // newer than its recorded versions and is built again next time, never the other way round.
            val markdown = notes.readOrNull(path)?.text ?: return@mapNotNull null
            val entry = build(path, markdown) { it in versions }
            cache[path] = Cached(entry, InkNoteFiles.dependenciesOf(path, markdown).associateWith { versions[it] })
            entry
        }
    }

    /** Every note, in no particular order. */
    fun all(): List<Entry> = entries(notes.list())
}
