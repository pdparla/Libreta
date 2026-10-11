package com.parla.libreta.core.note

/**
 * What the editor holds: the current [content] and [category], and what they were when the note was opened.
 * [origin] is the stored note it came from, or null for a note that was never saved.
 */
data class NoteDraft(
    val origin: NoteEntry?,
    val originalContent: String,
    val content: String,
    val originalCategory: String,
    val category: String,
) {
    val isModified: Boolean get() = content != originalContent || category != originalCategory

    fun edit(newContent: String): NoteDraft = copy(content = newContent)

    fun moveTo(newCategory: String): NoteDraft = copy(category = NoteNaming.categoryName(newCategory))

    /** The same draft as stored in [note]: nothing left to save. */
    fun savedAs(note: NoteEntry): NoteDraft =
        NoteDraft(note, content, content, note.path.category, note.path.category)

    companion object {
        fun blank(category: String = ""): NoteDraft {
            val clean = NoteNaming.categoryName(category)
            return NoteDraft(origin = null, originalContent = "", content = "", originalCategory = clean, category = clean)
        }

        fun of(note: NoteEntry, content: String): NoteDraft =
            NoteDraft(note, content, content, note.path.category, note.path.category)
    }
}

sealed class SaveResult {

    /** The draft is stored at [note]. Its path changes when the title (first line) or the category changed. */
    data class Saved(val note: NoteEntry) : SaveResult()

    /**
     * The note changed on disk while it was being edited, usually because a sync brought a newer version. That
     * version was left untouched, and the draft was stored next to it as a conflict copy, [note].
     */
    data class SavedAsConflictCopy(val note: NoteEntry, val original: NotePath) : SaveResult()

    /** Nothing to store: the draft is unchanged, or it is a new note without text. */
    data object NothingToSave : SaveResult()
}

/**
 * Use cases of the editor on top of the local [NoteRepository]. Like in Nextcloud Notes, the file is named after
 * the title and lives in its category's folder. A save never overwrites a version the editor has not seen.
 */
class NoteService(
    private val notes: NoteRepository,
    private val conflictNamer: ConflictNamer,
) {

    /** @throws NoteNotFoundException */
    fun open(path: NotePath): NoteDraft {
        val note = notes.read(path)
        return NoteDraft.of(NoteEntry(path, note.version), note.content)
    }

    /** Stores [draft]. The editor must continue from the returned note, which may have a new path. */
    fun save(draft: NoteDraft): SaveResult {
        if (!draft.isModified) return SaveResult.NothingToSave
        val origin = draft.origin
            ?: return if (draft.content.isBlank()) {
                SaveResult.NothingToSave
            } else {
                SaveResult.Saved(create(draft.content, draft.category))
            }

        val target = pathFor(draft, origin.path)
        return try {
            val version = if (target == origin.path) {
                notes.write(target, draft.content, WritePrecondition.MustMatch(origin.version))
            } else {
                moveTo(target, draft.content, origin)
            }
            SaveResult.Saved(NoteEntry(target, version))
        } catch (e: PreconditionFailedException) {
            saveAfterConcurrentChange(draft, origin)
        }
    }

    fun delete(path: NotePath) {
        try {
            notes.delete(path)
        } catch (e: NoteNotFoundException) {
            // Already gone.
        }
    }

    private fun create(content: String, category: String): NoteEntry {
        val path = NoteNaming.uniquePath(NoteNaming.titleOf(content), category, ::exists)
        return NoteEntry(path, notes.write(path, content, WritePrecondition.MustNotExist))
    }

    /** Renamed or moved: the new file is written first, so a failure never loses the text. */
    private fun moveTo(target: NotePath, content: String, origin: NoteEntry): Version {
        val version = notes.write(target, content, WritePrecondition.MustNotExist)
        try {
            notes.delete(origin.path, WritePrecondition.MustMatch(origin.version))
        } catch (e: NoteStoreException) {
            // The old file changed meanwhile (a sync brought a newer version) or is gone: keep whatever is there.
        }
        return version
    }

    private fun saveAfterConcurrentChange(draft: NoteDraft, origin: NoteEntry): SaveResult {
        if (!exists(origin.path)) {
            // Deleted elsewhere while being edited: the edit brings it back.
            return SaveResult.Saved(create(draft.content, draft.category))
        }
        // Next to the version it conflicts with, so both show up together.
        val copy = conflictNamer.conflictPathFor(origin.path, ::exists)
        val version = notes.write(copy, draft.content, WritePrecondition.MustNotExist)
        return SaveResult.SavedAsConflictCopy(NoteEntry(copy, version), origin.path)
    }

    /** Where the draft should live: in its category, named after its title (or keeping the name if the text is blank). */
    private fun pathFor(draft: NoteDraft, current: NotePath): NotePath {
        val title = if (draft.content.isBlank()) current.baseName else NoteNaming.titleOf(draft.content)
        return NoteNaming.uniquePath(
            title = title,
            category = draft.category,
            isTaken = { it != current && exists(it) },
            extension = current.extension,
        )
    }

    private fun exists(path: NotePath): Boolean = notes.readOrNull(path) != null
}
