package com.parla.libreta.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.core.note.NoteLinks
import com.parla.libreta.core.note.NoteNaming
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.core.note.readOrNull
import com.parla.libreta.ui.ink.InkNoteActivity

/**
 * Opens the note at [path], handwritten at [page] or as text; false if it no longer exists. Reads the note, so
 * call it where a short read is acceptable (it is one file).
 */
fun Activity.openNote(path: NotePath, page: Int = 0): Boolean {
    val markdown = container.notes.readOrNull(path)?.text ?: return false
    startActivity(
        if (container.notebookIndex.entry(path, markdown).isInk) InkNoteActivity.intent(this, path, page.coerceAtLeast(0))
        else NoteEditorActivity.intent(this, path),
    )
    return true
}

/**
 * Follows a `[[…]]` link (docs/guide/notes.md#links-between-notes) from the note at [from]: opens the note it names
 * or, if there is none, offers to create it, in the link's category if it names one and otherwise in [from]'s.
 */
fun Activity.followNoteLink(target: String, from: NotePath?) {
    container.background.execute({ NoteLinks.resolve(target, container.notes.list().map { it.path }, from) }) { result ->
        if (isFinishing || isDestroyed) return@execute
        val path = result.getOrNull()
        if (path != null && openNote(path)) return@execute
        val title = NoteNaming.fileBaseName(target.substringAfterLast('/').trim())
        val category = if ('/' in target) NoteNaming.categoryName(target.substringBeforeLast('/')) else from?.category.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.link_missing_title)
            .setMessage(getString(R.string.link_missing, title))
            .setPositiveButton(R.string.link_create) { _, _ ->
                try {
                    val created = container.noteService.create("$title\n\n", category)
                    startActivity(NoteEditorActivity.intent(this, created.path, edit = true))
                } catch (e: NoteStoreException) {
                    Toast.makeText(this, R.string.error_saving, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
