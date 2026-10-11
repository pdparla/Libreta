package com.parla.libreta.ui.ink

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
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
import com.parla.libreta.core.ink.Stroke
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.data.ai.TurnHandle
import com.parla.libreta.ui.CategoryPicker
import com.parla.libreta.ui.NoteEditorActivity
import com.parla.libreta.ui.ai.AiChatActivity
import com.parla.libreta.ui.ai.AiMessages
import com.parla.libreta.ui.dictation.Dictation
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handwritten note: write or draw with the stylus, page by page. Like the text editor, it saves on its own when it
 * stops being visible; a new note is only created once something has been drawn.
 *
 * The select tool marks a rectangle around strokes: drag it to move them, pull its corner to resize them, change
 * their colour or thickness, copy, paste or delete them. The AI works on it too (docs/07-ia.md): "Convertir a
 * texto" adds the recognised text to the note, "Conversar con IA" opens a conversation about it.
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
        inkView.onSelectionChange = ::updateControls
        inkView.onZoomChange = { showZoom() }
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
        findViewById<View>(R.id.ink_select).setOnClickListener { selectTool(InkView.Tool.SELECT) }
        // Undo and redo can change which strokes exist, and a selection belongs to the page it was marked on.
        findViewById<View>(R.id.ink_undo).setOnClickListener { editor?.undo(); inkView.clearSelection(); afterEdit() }
        findViewById<View>(R.id.ink_redo).setOnClickListener { editor?.redo(); inkView.clearSelection(); afterEdit() }
        findViewById<View>(R.id.ink_prev).setOnClickListener { editor?.let { it.goToPage(it.pageIndex - 1) }; afterPageChange() }
        findViewById<View>(R.id.ink_next).setOnClickListener { editor?.let { it.goToPage(it.pageIndex + 1) }; afterPageChange() }
        findViewById<View>(R.id.ink_add_page).setOnClickListener { editor?.addPage(); afterPageChange() }
        findViewById<View>(R.id.ink_zoom_in).setOnClickListener { inkView.zoomStep(up = true) }
        findViewById<View>(R.id.ink_zoom_out).setOnClickListener { inkView.zoomStep(up = false) }
        findViewById<View>(R.id.ink_zoom_level).setOnClickListener { inkView.resetZoom() }
        showZoom()
        setUpSelectionTools()
        findViewById<View>(R.id.ink_convert).setOnClickListener { convertSelection() }
        findViewById<View>(R.id.ink_dictate).setOnClickListener { Dictation.start(this) { showRecognized(it, R.string.stt_dictated_title) } }
        findViewById<View>(R.id.ink_chat).setOnClickListener { chatAboutSelection() }

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
        if (tool != InkView.Tool.SELECT) inkView.clearSelection()
        inkView.tool = tool
        updateControls()
    }

    /** Another page: no selection, the same zoom at the new page's top-left corner. */
    private fun afterPageChange() {
        inkView.clearSelection()
        inkView.showPageStart()
        updateControls()
        scheduleAutosave()
    }

    private fun showZoom() {
        val zoom = inkView.zoom
        val label = if (zoom % 1f == 0f) zoom.toInt().toString() else String.format(Locale.getDefault(), "%.1f", zoom)
        findViewById<TextView>(R.id.ink_zoom_level).text = getString(R.string.ink_zoom_level, label)
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
        findViewById<View>(R.id.ink_select).isSelected = inkView.tool == InkView.Tool.SELECT
        findViewById<View>(R.id.ink_undo).isEnabled = editor?.canUndo == true
        findViewById<View>(R.id.ink_redo).isEnabled = editor?.canRedo == true
        findViewById<View>(R.id.ink_prev).isEnabled = editor != null && editor.pageIndex > 0
        findViewById<View>(R.id.ink_next).isEnabled = editor != null && editor.pageIndex < editor.pageCount - 1
        pageLabel.text = editor?.let { getString(R.string.ink_page, it.pageIndex + 1, it.pageCount) }.orEmpty()
        findViewById<View>(R.id.ink_pen_options).visibility = visibleIf(inkView.tool == InkView.Tool.PEN)
        findViewById<View>(R.id.ink_eraser_options).visibility = visibleIf(inkView.tool == InkView.Tool.ERASER)
        findViewById<View>(R.id.ink_select_options).visibility = visibleIf(inkView.tool == InkView.Tool.SELECT)
        val selected = inkView.selection != null
        val holdsInk = inkView.selectedStrokes.isNotEmpty()
        findViewById<View>(R.id.ink_select_hint).visibility = if (holdsInk) View.GONE else View.VISIBLE
        findViewById<View>(R.id.ink_selection_edit).visibility = visibleIf(holdsInk)
        findViewById<View>(R.id.ink_paste).visibility = visibleIf(!InkClipboard.isEmpty)
        findViewById<View>(R.id.ink_convert).isEnabled = selected
        findViewById<View>(R.id.ink_chat).isEnabled = selected
        selectedStrokes().firstOrNull()?.let { first ->
            findViewById<View>(R.id.ink_selection_color).background = ColorPalette.swatchDrawable(first.color, false, resources.displayMetrics.density)
        }
        colorView.background = ColorPalette.swatchDrawable(inkView.penColor, false, resources.displayMetrics.density)
        widthViews.forEachIndexed { i, view -> view.isSelected = widths[i] == inkView.penWidth }
        findViewById<View>(R.id.ink_erase_area).isSelected = !inkView.eraseWholeStrokes
        findViewById<View>(R.id.ink_erase_strokes).isSelected = inkView.eraseWholeStrokes
        eraserRadiusViews.forEachIndexed { i, view -> view.isSelected = eraserRadii[i] == inkView.eraserRadius }
    }

    private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    private fun selectedStrokes(): List<Stroke> {
        val page = editor?.page ?: return emptyList()
        return inkView.selectedStrokes.mapNotNull { page.strokes.getOrNull(it) }
    }

    private fun setUpSelectionTools() {
        val colorView = findViewById<View>(R.id.ink_selection_color)
        colorView.setOnClickListener {
            val current = selectedStrokes().firstOrNull()?.color ?: inkView.penColor
            ColorPalette.show(colorView, current) { color ->
                editor?.restyleStrokes(inkView.selectedStrokes, color = color)
                afterSelectionEdit()
            }
        }
        findViewById<View>(R.id.ink_selection_width).setOnClickListener {
            val labels = listOf(R.string.ink_width_thin, R.string.ink_width_medium, R.string.ink_width_thick).map(::getString)
            AlertDialog.Builder(this)
                .setTitle(R.string.ink_selection_width)
                .setItems(labels.toTypedArray()) { _, which ->
                    editor?.restyleStrokes(inkView.selectedStrokes, width = widths[which])
                    afterSelectionEdit()
                }
                .show()
        }
        findViewById<View>(R.id.ink_selection_copy).setOnClickListener {
            InkClipboard.copy(selectedStrokes())
            Toast.makeText(this, R.string.ink_copied, Toast.LENGTH_SHORT).show()
            updateControls()
        }
        findViewById<View>(R.id.ink_selection_delete).setOnClickListener {
            editor?.deleteStrokes(inkView.selectedStrokes)
            inkView.clearSelection()
            afterEdit()
        }
        findViewById<View>(R.id.ink_paste).setOnClickListener {
            val editor = editor ?: return@setOnClickListener
            // The pasted strokes come selected, ready to be moved where they belong.
            val pasted = editor.insertStrokes(InkClipboard.placedOn(editor.page))
            inkView.refresh()
            inkView.selectStrokes(pasted)
            afterSelectionEdit()
        }
    }

    /** After changing the selected strokes: redraw them, keep them selected (the rectangle may have grown) and save soon. */
    private fun afterSelectionEdit() {
        inkView.refresh()
        inkView.selectStrokes(inkView.selectedStrokes)
        updateControls()
        scheduleAutosave()
    }

    /** The selection as an image for the model; null (after saying why) if it holds no ink. */
    private fun selectionImage(): ByteArray? {
        val editor = editor ?: return null
        val area = inkView.selection ?: return null
        if (!area.hasInk(editor.page)) {
            Toast.makeText(this, R.string.ink_selection_empty, Toast.LENGTH_SHORT).show()
            return null
        }
        return InkRenderer.renderSelection(editor.page, area)
    }

    private fun signedIn(): Boolean {
        val signedIn = container.chatGptSessions.load() != null
        if (!signedIn) AiMessages.showSignInNeeded(this)
        return signedIn
    }

    private fun convertSelection() {
        if (!signedIn()) return
        val image = selectionImage() ?: return
        val handle = TurnHandle()
        @Suppress("DEPRECATION") // ProgressDialog is the progress dialog API 19 has.
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.ai_converting))
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            setOnCancelListener { handle.cancel() }
            show()
        }
        container.aiBackground.execute({ container.ai.convertToText(image, handle) }) { result ->
            if (isDestroyed) return@execute
            progress.dismiss()
            if (handle.isCancelled) return@execute
            result
                .onSuccess { text ->
                    if (text.isBlank()) Toast.makeText(this, R.string.ai_convert_nothing, Toast.LENGTH_LONG).show() else showRecognized(text, R.string.ai_convert_title)
                }
                .onFailure { Toast.makeText(this, AiMessages.failure(this, it), Toast.LENGTH_LONG).show() }
        }
    }

    /** Recognised or dictated text, editable before it goes into the note. */
    private fun showRecognized(text: String, title: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            setText(text)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.ai_add_to_note) { _, _ -> addToNote(input.text.toString()) }
            .setNeutralButton(R.string.ai_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(title), input.text.toString()))
                Toast.makeText(this, R.string.ai_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addToNote(text: String) {
        save()
        val current = note ?: return
        runCatchingStore {
            container.inkNotes.addText(current, text)
            Toast.makeText(this, R.string.ai_added_to_note, Toast.LENGTH_SHORT).show()
        }
    }

    private fun chatAboutSelection() {
        if (!signedIn()) return
        val image = selectionImage() ?: return
        save()
        val file = File(cacheDir, AiChatActivity.SELECTION_FILE)
        try {
            file.writeBytes(image)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.error_saving, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(AiChatActivity.intent(this, file, note?.path?.category ?: category))
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
        startActivity(NoteEditorActivity.intent(this, current.path, edit = true))
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
