package com.parla.libreta.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.core.note.NoteDraft
import com.parla.libreta.core.note.NoteEntry
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.core.note.SaveResult
import com.parla.libreta.core.note.Version
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import java.io.File

/**
 * A text note, like in Nextcloud Notes: it opens formatted (Markdown with its images, see [Markdown.forNote]) and
 * "Editar" switches to the Markdown, highlighted as it is typed; "Ver" goes back. New notes, and notes opened to
 * edit their text, start in the editor. It saves on its own whenever it stops being visible or goes back to the
 * formatted view; there is no save button.
 *
 * Saving happens on the main thread on purpose: a note is a few KB, and a synchronous write in onPause is the
 * only way to be sure the text is on disk before the process can be killed.
 */
class NoteEditorActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var preview: TextView
    private lateinit var draft: NoteDraft
    private var deleted = false
    private var editing = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        editor = findViewById(R.id.editor)
        preview = findViewById(R.id.preview)
        // Before the text is set, so the highlighting covers it from the start.
        editor.addTextChangedListener(MarkwonEditorTextWatcher.withProcess(Markdown.editor(this)))

        val restored = savedInstanceState?.let(::restoreDraft)
        draft = restored ?: openDraft() ?: return
        // After a configuration change the EditText restores its own text, cursor included.
        if (restored == null) editor.setText(draft.content)
        editing = savedInstanceState?.getBoolean(STATE_EDITING)
            ?: (draft.origin == null || draft.content.isBlank() || intent.getBooleanExtra(EXTRA_EDIT, false))
        if (draft.origin == null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        showMode()
        showTitle()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Save first: the stored draft must point at the version on disk, or the next save would see a conflict.
        save()
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, draft.origin?.path?.value)
        outState.putString(STATE_VERSION, draft.origin?.version?.value)
        outState.putString(STATE_ORIGINAL, draft.originalContent)
        outState.putString(STATE_ORIGINAL_CATEGORY, draft.originalCategory)
        outState.putString(STATE_CATEGORY, draft.category)
        outState.putBoolean(STATE_EDITING, editing)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.note_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_toggle_mode).setTitle(if (editing) R.string.action_view else R.string.action_edit)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        R.id.action_toggle_mode -> {
            if (editing) save()
            editing = !editing
            showMode()
            true
        }
        R.id.action_category -> {
            chooseCategory()
            true
        }
        R.id.action_delete -> {
            confirmDelete()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openDraft(): NoteDraft? {
        val path = intent.getStringExtra(EXTRA_PATH)?.let(NotePath::parseOrNull)
            ?: return NoteDraft.blank(intent.getStringExtra(EXTRA_CATEGORY).orEmpty())
        return try {
            container.noteService.open(path)
        } catch (e: NoteStoreException) {
            Toast.makeText(this, R.string.error_opening, Toast.LENGTH_LONG).show()
            finish()
            null
        }
    }

    private fun save() {
        if (deleted || !::draft.isInitialized) return
        val edited = draft.edit(editor.text.toString())
        val result = try {
            container.noteService.save(edited)
        } catch (e: NoteStoreException) {
            // The text stays in the editor; the next pause tries again.
            Toast.makeText(this, R.string.error_saving, Toast.LENGTH_LONG).show()
            return
        }
        when (result) {
            is SaveResult.Saved -> draft = edited.savedAs(result.note)
            is SaveResult.SavedAsConflictCopy -> {
                draft = edited.savedAs(result.note)
                val message = getString(R.string.saved_as_conflict_copy, result.original.baseName, result.note.path.baseName)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            SaveResult.NothingToSave -> draft = edited
        }
        showTitle()
    }

    /** Shows the editor, or the note formatted from the editor's text, with or without the keyboard. */
    private fun showMode() {
        findViewById<View>(R.id.preview_scroll).visibility = if (editing) View.GONE else View.VISIBLE
        editor.visibility = if (editing) View.VISIBLE else View.GONE
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (editing) {
            editor.requestFocus()
            keyboard.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        } else {
            keyboard.hideSoftInputFromWindow(editor.windowToken, 0)
            // Images are linked relative to the note's folder, i.e. its category as saved.
            val folder = File(container.notesDir, draft.origin?.path?.category ?: draft.category)
            Markdown.forNote(this, folder).setMarkdown(preview, editor.text.toString())
        }
        invalidateOptionsMenu()
    }

    /** The move to the chosen category happens on the next save. */
    private fun chooseCategory() = CategoryPicker.show(this, draft.category, ::moveTo)

    private fun moveTo(category: String) {
        draft = draft.moveTo(category)
        showTitle()
    }

    private fun confirmDelete() {
        val origin = draft.origin
        if (origin == null && editor.text.isBlank()) {
            deleted = true
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, origin?.path?.baseName ?: getString(R.string.new_note)))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                deleted = true
                origin?.let { container.noteService.delete(it.path) }
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTitle() {
        title = draft.origin?.path?.baseName ?: getString(R.string.new_note)
        actionBar?.subtitle = draft.category.ifEmpty { null }
    }

    private fun restoreDraft(state: Bundle): NoteDraft {
        val path = state.getString(STATE_PATH)?.let(NotePath::parseOrNull)
        val version = state.getString(STATE_VERSION)
        val original = state.getString(STATE_ORIGINAL).orEmpty()
        val originalCategory = state.getString(STATE_ORIGINAL_CATEGORY).orEmpty()
        val origin = if (path != null && version != null) NoteEntry(path, Version(version)) else null
        return NoteDraft(origin, original, original, originalCategory, state.getString(STATE_CATEGORY).orEmpty())
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_EDIT = "edit"
        private const val STATE_EDITING = "editing"
        private const val STATE_PATH = "draft.path"
        private const val STATE_VERSION = "draft.version"
        private const val STATE_ORIGINAL = "draft.original"
        private const val STATE_ORIGINAL_CATEGORY = "draft.originalCategory"
        private const val STATE_CATEGORY = "draft.category"

        /** Opens the note at [path] formatted, or straight in the editor when [edit]. */
        fun intent(context: Context, path: NotePath, edit: Boolean = false): Intent =
            Intent(context, NoteEditorActivity::class.java).putExtra(EXTRA_PATH, path.value).putExtra(EXTRA_EDIT, edit)

        fun intentForNew(context: Context, category: String): Intent =
            Intent(context, NoteEditorActivity::class.java).putExtra(EXTRA_CATEGORY, category)
    }
}
