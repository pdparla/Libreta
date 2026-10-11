package com.parla.libreta.ui.ink

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.core.ink.InkDocument
import com.parla.libreta.core.ink.InkEditor
import com.parla.libreta.core.ink.InkNote
import com.parla.libreta.core.ink.Paper
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.ui.CategoryPicker
import com.parla.libreta.ui.NoteEditorActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handwritten note: write or draw with the stylus, page by page. Like the text editor, it saves on its own when it
 * stops being visible; a new note is only created once something has been drawn.
 */
class InkNoteActivity : Activity() {

    private lateinit var inkView: InkView
    private lateinit var pageLabel: TextView
    private var editor: InkEditor? = null
    private var note: InkNote? = null
    private var category = ""
    private var deleted = false
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { save() }

    private val widths = listOf(2f, 3.5f, 6f)
    private val eraserRadii = listOf(5f, 10f, 22f)
    private lateinit var colorView: View
    private lateinit var widthViews: List<View>
    private lateinit var eraserRadiusViews: List<View>
    private val toolPreferences by lazy { getSharedPreferences(PREFERENCES_TOOLS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ink_note)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        inkView = findViewById(R.id.ink_view)
        pageLabel = findViewById(R.id.ink_page)
        inkView.maxTouchMajor = PALM_TOUCH_MAJOR
        inkView.onChange = ::updateControls
        // Autosave: once the page has been left alone for a moment, not while writing.
        inkView.onTouchStart = { handler.removeCallbacks(autosave) }
        inkView.onTouchEnd = ::scheduleAutosave
        setUpToolbar()

        val path = (savedInstanceState?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_PATH))?.let(NotePath::parseOrNull)
        if (path != null) {
            // A note's strokes are a few hundred KB at most: reading them here keeps the page from appearing blank.
            note = try {
                container.inkNotes.open(path)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.ink_error_open, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            show(InkEditor(note!!.document))
        } else {
            category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
            // New note: the page takes the size of the writing area once it is laid out.
            inkView.onSizeKnown = { width, height -> if (editor == null) show(InkEditor(InkDocument.blank(width, height))) }
        }
        updateTitle()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autosave)
        save()
        saveToolPreferences()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        save()
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, note?.path?.value)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ink_note, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_rename -> askTitle()
            R.id.action_category -> CategoryPicker.show(this, note?.path?.category ?: category, ::moveTo)
            R.id.action_paper -> choosePaper()
            R.id.action_edit_text -> editText()
            R.id.action_delete -> confirmDelete()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun show(newEditor: InkEditor) {
        editor = newEditor
        inkView.editor = newEditor
        updateControls()
    }

    private fun setUpToolbar() {
        findViewById<View>(R.id.ink_pen).setOnClickListener { selectTool(InkView.Tool.PEN) }
        findViewById<View>(R.id.ink_eraser).setOnClickListener { selectTool(InkView.Tool.ERASER) }
        findViewById<View>(R.id.ink_undo).setOnClickListener { editor?.undo(); afterEdit() }
        findViewById<View>(R.id.ink_redo).setOnClickListener { editor?.redo(); afterEdit() }
        findViewById<View>(R.id.ink_prev).setOnClickListener { editor?.let { it.goToPage(it.pageIndex - 1) }; afterEdit() }
        findViewById<View>(R.id.ink_next).setOnClickListener { editor?.let { it.goToPage(it.pageIndex + 1) }; afterEdit() }
        findViewById<View>(R.id.ink_add_page).setOnClickListener { editor?.addPage(); afterEdit() }

        colorView = findViewById(R.id.ink_color)
        colorView.setOnClickListener {
            ColorPalette.show(inkView, inkView.penColor) { color ->
                inkView.penColor = color
                selectTool(InkView.Tool.PEN)
            }
        }
        widthViews = listOf(R.id.ink_width_thin, R.id.ink_width_medium, R.id.ink_width_thick).map { findViewById(it) }
        widthViews.forEachIndexed { i, view ->
            view.setOnClickListener {
                inkView.penWidth = widths[i]
                selectTool(InkView.Tool.PEN)
            }
        }
        findViewById<View>(R.id.ink_erase_area).setOnClickListener { inkView.eraseWholeStrokes = false; updateControls() }
        findViewById<View>(R.id.ink_erase_strokes).setOnClickListener { inkView.eraseWholeStrokes = true; updateControls() }
        eraserRadiusViews = listOf(R.id.ink_eraser_small, R.id.ink_eraser_medium, R.id.ink_eraser_large).map { findViewById(it) }
        eraserRadiusViews.forEachIndexed { i, view ->
            view.setOnClickListener {
                inkView.eraserRadius = eraserRadii[i]
                updateControls()
            }
        }

        // The last pen and eraser used, in any note.
        inkView.penColor = toolPreferences.getInt(PREF_PEN_COLOR, ColorPalette.defaultColor)
        inkView.penWidth = toolPreferences.getFloat(PREF_PEN_WIDTH, widths[1]).takeIf { it in widths } ?: widths[1]
        inkView.eraseWholeStrokes = toolPreferences.getBoolean(PREF_ERASE_STROKES, false)
        inkView.eraserRadius = toolPreferences.getFloat(PREF_ERASER_RADIUS, eraserRadii[1]).takeIf { it in eraserRadii } ?: eraserRadii[1]
        selectTool(InkView.Tool.PEN)
    }

    private fun selectTool(tool: InkView.Tool) {
        inkView.tool = tool
        updateControls()
    }

    private fun afterEdit() {
        inkView.refresh()
        updateControls()
        scheduleAutosave()
    }

    /** Saves [AUTOSAVE_DELAY_MS] after the last touch, so that a crash or a dead battery loses little. */
    private fun scheduleAutosave() {
        handler.removeCallbacks(autosave)
        if (editor?.isModified == true) handler.postDelayed(autosave, AUTOSAVE_DELAY_MS)
    }

    private fun updateControls() {
        val editor = editor
        findViewById<View>(R.id.ink_pen).isSelected = inkView.tool == InkView.Tool.PEN
        findViewById<View>(R.id.ink_eraser).isSelected = inkView.tool == InkView.Tool.ERASER
        findViewById<View>(R.id.ink_undo).isEnabled = editor?.canUndo == true
        findViewById<View>(R.id.ink_redo).isEnabled = editor?.canRedo == true
        findViewById<View>(R.id.ink_prev).isEnabled = editor != null && editor.pageIndex > 0
        findViewById<View>(R.id.ink_next).isEnabled = editor != null && editor.pageIndex < editor.pageCount - 1
        pageLabel.text = editor?.let { getString(R.string.ink_page, it.pageIndex + 1, it.pageCount) }.orEmpty()
        val erasing = inkView.tool == InkView.Tool.ERASER
        findViewById<View>(R.id.ink_pen_options).visibility = if (erasing) View.GONE else View.VISIBLE
        findViewById<View>(R.id.ink_eraser_options).visibility = if (erasing) View.VISIBLE else View.GONE
        colorView.background = ColorPalette.swatchDrawable(inkView.penColor, false, resources.displayMetrics.density)
        widthViews.forEachIndexed { i, view -> view.isSelected = widths[i] == inkView.penWidth }
        findViewById<View>(R.id.ink_erase_area).isSelected = !inkView.eraseWholeStrokes
        findViewById<View>(R.id.ink_erase_strokes).isSelected = inkView.eraseWholeStrokes
        eraserRadiusViews.forEachIndexed { i, view -> view.isSelected = eraserRadii[i] == inkView.eraserRadius }
    }

    private fun saveToolPreferences() {
        toolPreferences.edit()
            .putInt(PREF_PEN_COLOR, inkView.penColor)
            .putFloat(PREF_PEN_WIDTH, inkView.penWidth)
            .putBoolean(PREF_ERASE_STROKES, inkView.eraseWholeStrokes)
            .putFloat(PREF_ERASER_RADIUS, inkView.eraserRadius)
            .apply()
    }

    private fun save() {
        val editor = editor ?: return
        if (deleted) return
        try {
            val current = note
            if (current == null) {
                if (!editor.isModified) return
                note = container.inkNotes.create(category, defaultTitle(), editor.document)
            } else if (editor.isModified) {
                note = container.inkNotes.save(current, editor.document)
            }
            editor.markSaved()
            updateTitle()
        } catch (e: NoteStoreException) {
            Toast.makeText(this, R.string.error_saving, Toast.LENGTH_LONG).show()
        }
    }

    private fun defaultTitle() =
        getString(R.string.ink_default_title, SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date()))

    private fun updateTitle() {
        val path = note?.path
        title = path?.baseName ?: getString(R.string.new_note)
        actionBar?.subtitle = (path?.category ?: category).ifEmpty { null }
    }

    private fun askTitle() {
        save()
        val current = note ?: return Toast.makeText(this, R.string.ink_draw_first, Toast.LENGTH_SHORT).show()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(current.path.baseName)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) runCatchingStore { note = container.inkNotes.rename(current, title) }
                updateTitle()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun moveTo(newCategory: String) {
        save()
        val current = note
        if (current == null) category = newCategory else runCatchingStore { note = container.inkNotes.move(current, newCategory) }
        updateTitle()
    }

    private fun choosePaper() {
        val papers = listOf(Paper.LINED, Paper.GRID, Paper.BLANK)
        val labels = listOf(R.string.ink_paper_lined, R.string.ink_paper_grid, R.string.ink_paper_blank).map(::getString)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_paper)
            .setItems(labels.toTypedArray()) { _, which ->
                editor?.setPaper(papers[which])
                afterEdit()
            }
            .show()
    }

    /** Opens the note's Markdown in the text editor, to add or correct text around the pages. */
    private fun editText() {
        save()
        val current = note ?: return Toast.makeText(this, R.string.ink_draw_first, Toast.LENGTH_SHORT).show()
        startActivity(NoteEditorActivity.intent(this, current.path))
        finish()
    }

    private fun confirmDelete() {
        val current = note
        if (current == null) {
            deleted = true
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, current.path.baseName))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                deleted = true
                runCatchingStore { container.noteService.delete(current.path) }
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inline fun runCatchingStore(block: () -> Unit) {
        try {
            block()
        } catch (e: NoteStoreException) {
            Toast.makeText(this, R.string.error_saving, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_CATEGORY = "category"
        private const val STATE_PATH = "ink.path"

        /**
         * Pause after the last touch before saving. Long enough not to save between words (saving renders the
         * page, which the tablet notices while drawing), short enough to lose only the last line.
         */
        private const val AUTOSAVE_DELAY_MS = 3_000L
        private const val PREFERENCES_TOOLS = "ink_tools"
        private const val PREF_PEN_COLOR = "pen_color"
        private const val PREF_PEN_WIDTH = "pen_width"
        private const val PREF_ERASE_STROKES = "erase_strokes"
        private const val PREF_ERASER_RADIUS = "eraser_radius"

        /** Contacts larger than this are a palm. Calibrated with the AmberVec stylus (see docs/06). */
        private const val PALM_TOUCH_MAJOR = 0f

        fun intent(context: Context, path: NotePath): Intent =
            Intent(context, InkNoteActivity::class.java).putExtra(EXTRA_PATH, path.value)

        fun intentForNew(context: Context, category: String): Intent =
            Intent(context, InkNoteActivity::class.java).putExtra(EXTRA_CATEGORY, category)
    }
}
