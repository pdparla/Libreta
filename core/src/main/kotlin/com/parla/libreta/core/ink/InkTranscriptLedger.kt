package com.parla.libreta.core.ink

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.Version
import java.util.concurrent.ConcurrentHashMap

/**
 * What the background transcriber has already seen to be settled (docs/design/ai.md): notes with nothing left to
 * read. For each, the versions of the note, its strokes and its transcripts at that moment; while all of them are
 * unchanged the note need not be opened, decoded or hashed again. A missing file is a null version and is compared
 * too, so a strokes file appearing unsettles the note. Safe to use from several threads.
 */
class InkTranscriptLedger {

    private val settled = ConcurrentHashMap<NotePath, Map<NotePath, Version?>>()

    /** Whether the note at [path] was settled and none of the files it depends on has changed since. */
    fun isSettled(path: NotePath, versions: Map<NotePath, Version>): Boolean {
        val recorded = settled[path] ?: return false
        return recorded.all { (file, version) -> versions[file] == version }
    }

    /**
     * Records that the note at [path], whose Markdown is [markdown], had nothing left to read when [versions] was
     * listed. [versions] must come from a listing made before the note's content was read, so that a later change
     * can only make the record stale, never wrong.
     */
    fun markSettled(path: NotePath, markdown: String, versions: Map<NotePath, Version>) {
        settled[path] = InkNoteFiles.dependenciesOf(path, markdown).associateWith { versions[it] }
    }

    /** Forgets the notes that are not in [paths] any more. */
    fun retainOnly(paths: Set<NotePath>) {
        (settled as MutableMap<NotePath, Map<NotePath, Version?>>).keys.retainAll(paths)
    }
}
