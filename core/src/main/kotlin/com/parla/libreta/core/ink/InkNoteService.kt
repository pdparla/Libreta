package com.parla.libreta.core.ink

import com.parla.libreta.core.note.NoteNaming
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.note.NoteService
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.core.note.PreconditionFailedException
import com.parla.libreta.core.note.SaveResult
import com.parla.libreta.core.note.WritePrecondition
import com.parla.libreta.core.note.readOrNull
import com.parla.libreta.core.note.writeText

/** Draws a page as an image (PNG) for Nextcloud Notes; implemented with Android's Canvas in the app. */
fun interface PageRenderer {
    fun render(page: InkPage): ByteArray
}

/** A stored handwritten note: its Markdown file, its strokes and its attachments folder (e.g. `.attachments.k3`). */
data class InkNote(val path: NotePath, val document: InkDocument, val folder: String)

/**
 * Creates, opens and saves handwritten notes on top of the local [NoteRepository]. Files are written in an order
 * that never leaves a note pointing at missing pages: strokes and page images first, Markdown last.
 *
 * Renaming, moving and deleting go through [NoteService], which already keeps attachments with their note.
 */
class InkNoteService(
    private val notes: NoteRepository,
    private val noteService: NoteService,
    private val renderer: PageRenderer,
    private val newFolderId: () -> String,
) {

    fun create(category: String, title: String, document: InkDocument): InkNote {
        val folder = NotePath.ATTACHMENTS_FOLDER_PREFIX + newFolderId()
        val path = NoteNaming.uniquePath(title, category, isTaken = notes::exists)
        writeAttachments(path, folder, document, previous = null)
        notes.writeText(path, InkNoteFormat.compose(title.trim(), folder, document.pages.size, InkOutline.of(document)), WritePrecondition.MustNotExist)
        return InkNote(path, document, folder)
    }

    /** @throws IllegalArgumentException if the note at [path] is not a handwritten note or its strokes are unreadable. */
    fun open(path: NotePath): InkNote {
        val markdown = notes.read(path).text
        for (inkFile in InkNoteFormat.inkFileCandidates(markdown)) {
            val folder = InkNoteFormat.folderOf(inkFile) ?: continue
            val strokes = path.resolveSibling(inkFile)?.let(notes::readOrNull) ?: continue
            return InkNote(path, InkJson.decode(strokes.text), folder)
        }
        throw IllegalArgumentException("$path has no strokes")
    }

    /** Whether the note at [path], with [markdown], is handwritten; [exists] tells whether a file is there. */
    fun isInkNote(path: NotePath, markdown: String, exists: (NotePath) -> Boolean): Boolean =
        InkNoteFormat.inkFileCandidates(markdown).any { inkFile -> path.resolveSibling(inkFile)?.let(exists) == true }

    /**
     * Saves [document] into [note]: strokes, images of the pages that changed, then the Markdown with one image
     * per page. If the Markdown changed meanwhile (typed text, recognised text), the page links are merged into
     * the new version instead of overwriting it.
     */
    fun save(note: InkNote, document: InkDocument): InkNote {
        writeAttachments(note.path, note.folder, document, previous = note.document)
        updateMarkdown(note) { InkNoteFormat.update(it, note.folder, document.pages.size, InkOutline.of(document)) }
        return note.copy(document = document)
    }

    /** Adds [text] (e.g. recognised from the handwriting) to the note's Markdown, above the page images. */
    fun addText(note: InkNote, text: String) {
        updateMarkdown(note) { InkNoteFormat.withText(it, note.folder, note.document.pages.size, text, InkOutline.of(note.document)) }
    }

    /** Rewrites the note's Markdown with [edit], merging again if it changes between the read and the write. */
    private fun updateMarkdown(note: InkNote, edit: (String) -> String) {
        repeat(MERGE_ATTEMPTS) {
            val current = notes.read(note.path)
            val updated = edit(current.text)
            if (updated == current.text) return
            try {
                notes.writeText(note.path, updated, WritePrecondition.MustMatch(current.version))
                return
            } catch (e: PreconditionFailedException) {
                // Changed between read and write: read it again and merge once more.
            }
        }
        throw PreconditionFailedException(note.path)
    }

    /** Renames the note by replacing its first line; the attachments stay where they are. */
    fun rename(note: InkNote, title: String): InkNote {
        val draft = noteService.open(note.path)
        val lines = draft.content.lines()
        val body = if (lines.firstOrNull()?.startsWith("#") == true) lines.drop(1) else lines
        val result = noteService.save(draft.edit((listOf("# ${title.trim()}") + body).joinToString("\n")))
        return note.copy(path = pathOf(result) ?: note.path)
    }

    /** Moves the note and its attachments to [category]. */
    fun move(note: InkNote, category: String): InkNote {
        val result = noteService.save(noteService.open(note.path).moveTo(category))
        return note.copy(path = pathOf(result) ?: note.path)
    }

    private fun pathOf(result: SaveResult): NotePath? = when (result) {
        is SaveResult.Saved -> result.note.path
        is SaveResult.SavedAsConflictCopy -> result.note.path
        SaveResult.NothingToSave -> null
    }

    private fun writeAttachments(note: NotePath, folder: String, document: InkDocument, previous: InkDocument?) {
        val inFolder = { name: String -> note.resolveSibling("$folder/$name") ?: throw IllegalArgumentException("Bad folder $folder") }
        notes.writeText(inFolder(InkNoteFormat.INK_FILE), InkJson.encode(document))
        document.pages.forEachIndexed { i, page ->
            val image = inFolder(InkNoteFormat.pageImageName(i))
            // Marks are not drawn, so marking a title does not render the page again.
            val unchanged = previous?.pages?.getOrNull(i)?.copy(marks = emptyList()) == page.copy(marks = emptyList()) &&
                notes.exists(image)
            if (!unchanged) notes.write(image, renderer.render(page))
        }
        // Pages that no longer exist.
        val oldCount = previous?.pages?.size ?: 0
        for (i in document.pages.size until oldCount) {
            try {
                notes.delete(inFolder(InkNoteFormat.pageImageName(i)))
            } catch (e: NoteStoreException) {
                // Already gone.
            }
        }
    }

    private companion object {
        const val MERGE_ATTEMPTS = 3
    }
}
