package com.parla.libreta.ui.ink

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.core.ink.InkDocument
import com.parla.libreta.core.ink.InkEditor
import com.parla.libreta.core.ink.InkMark
import com.parla.libreta.core.ink.InkOutline
import com.parla.libreta.core.ink.InkPage
import com.parla.libreta.core.ink.InkRect
import com.parla.libreta.core.ink.InkSizes
import com.parla.libreta.core.ink.InkTranscripts
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/**
 * Handwritten note: write or draw with the stylus, page by page. Like the text editor, it saves on its own when it
 * stops being visible; a new note is only created once something has been drawn.
 *
 * The select tool marks a rectangle around strokes: drag it to move them, pull its corner to resize them, change
 * their colour or thickness, copy, paste or delete them. The AI works on it too (docs/07-ia.md): "Convertir a
 * texto" adds the recognised text to the note, "Conversar con IA" opens a conversation about it. The paint bucket
 * fills a shape with the pen's colour (docs/15-relleno.md).
 */
class InkNoteActivity : Activity() {

    private lateinit var inkView: InkView
    private lateinit var pageLabel: TextView
    private var editor: InkEditor? = null
    private var note: InkNote? = null
    private var category = ""
    private var deleted = false
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { autosave() }

    /**
     * Autosave renders and compresses the pages, which janks the UI on this tablet, so it runs on [saver]. The
     * document is immutable: the snapshot taken on the UI thread is what gets saved. At most one background save
     * is [pending] at a time, and it is only ever touched on the UI thread, which also owns [note]: the task gets
     * the note to save from and returns the new one. Every other save (pause, rename, move, AI actions, delete)
     * first calls [awaitPending], so saves never overlap and none starts from a stale note or path.
     */
    private val saver: ExecutorService = Executors.newSingleThreadExecutor()
    private var pending: PendingSave? = null
    private var destroyed = false

    private class PendingSave(val snapshot: InkDocument, val result: Future<InkNote>)

    // The toolbar's views, looked up once: updateControls runs after every stroke.
    private lateinit var toolPen: View
    private lateinit var toolEraser: View
    private lateinit var toolSelect: View
    private lateinit var toolFill: View
    private lateinit var undoButton: View
    private lateinit var redoButton: View
    private lateinit var prevButton: View
    private lateinit var nextButton: View
    private lateinit var penOptions: View
    private lateinit var eraserOptions: View
    private lateinit var selectOptions: View
    private lateinit var fillOptions: View
    private lateinit var selectHint: View
    private lateinit var selectionEdit: View
    private lateinit var pasteButton: View
    private lateinit var convertButton: View
    private lateinit var chatButton: View
    private lateinit var titleButton: View
    private lateinit var tagButton: View
    private lateinit var selectionColor: View
    private lateinit var eraseAreaButton: View
    private lateinit var eraseStrokesButton: View
    private lateinit var zoomLevel: TextView
    private lateinit var penPreview: SizePreview
    private lateinit var eraserPreview: SizePreview
    private lateinit var penSizeLabel: TextView
    private lateinit var eraserSizeLabel: TextView
    private lateinit var colorView: View
    private lateinit var fillColorView: View
    private lateinit var penSize: SeekBar
    private lateinit var eraserSize: SeekBar

    /** The colours the swatches in the toolbar show now, to rebuild them only when they change (null: not yet). */
    private var penSwatch: Int? = null
    private var selectionSwatch: Int? = null
    private val toolPreferences by lazy { getSharedPreferences(PREFERENCES_TOOLS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ink_note)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        bindViews()
        inkView.maxTouchMajor = PALM_TOUCH_MAJOR
        inkView.onChange = ::updateControls
        // Autosave: once the page has been left alone for a moment, not while writing.
        inkView.onTouchStart = { handler.removeCallbacks(autosave) }
        inkView.onTouchEnd = ::scheduleAutosave
        inkView.onSelectionChange = ::updateControls
        inkView.onZoomChange = { showZoom() }
        inkView.onNothingToFill = { Toast.makeText(this, R.string.ink_fill_nothing, Toast.LENGTH_SHORT).show() }
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
            show(InkEditor(note!!.document).apply {
                // Opened from a search match on another page (docs/11-busqueda.md).
                if (savedInstanceState == null) goToPage(intent.getIntExtra(EXTRA_PAGE, 0))
            })
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
        // Read what changed in the background, for search; never while writing (docs/11-busqueda.md).
        container.inkTranscriber.request()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(autosave)
        // A save still running finishes; its completion sees `destroyed` and leaves the views alone.
        saver.shutdown()
        super.onDestroy()
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
            R.id.action_index -> showIndex()
            R.id.action_clean_copy -> cleanCopy()
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

    private fun bindViews() {
        inkView = findViewById(R.id.ink_view)
        pageLabel = findViewById(R.id.ink_page)
        toolPen = findViewById(R.id.ink_pen)
        toolEraser = findViewById(R.id.ink_eraser)
        toolSelect = findViewById(R.id.ink_select)
        toolFill = findViewById(R.id.ink_fill)
        undoButton = findViewById(R.id.ink_undo)
        redoButton = findViewById(R.id.ink_redo)
        prevButton = findViewById(R.id.ink_prev)
        nextButton = findViewById(R.id.ink_next)
        penOptions = findViewById(R.id.ink_pen_options)
        eraserOptions = findViewById(R.id.ink_eraser_options)
        selectOptions = findViewById(R.id.ink_select_options)
        fillOptions = findViewById(R.id.ink_fill_options)
        selectHint = findViewById(R.id.ink_select_hint)
        selectionEdit = findViewById(R.id.ink_selection_edit)
        pasteButton = findViewById(R.id.ink_paste)
        convertButton = findViewById(R.id.ink_convert)
        chatButton = findViewById(R.id.ink_chat)
        titleButton = findViewById(R.id.ink_title)
        tagButton = findViewById(R.id.ink_tag)
        selectionColor = findViewById(R.id.ink_selection_color)
        eraseAreaButton = findViewById(R.id.ink_erase_area)
        eraseStrokesButton = findViewById(R.id.ink_erase_strokes)
        zoomLevel = findViewById(R.id.ink_zoom_level)
        penPreview = findViewById(R.id.ink_pen_preview)
        eraserPreview = findViewById(R.id.ink_eraser_preview)
        penSizeLabel = findViewById(R.id.ink_pen_size_label)
        eraserSizeLabel = findViewById(R.id.ink_eraser_size_label)
        colorView = findViewById(R.id.ink_color)
        fillColorView = findViewById(R.id.ink_fill_color)
        penSize = findViewById(R.id.ink_pen_size)
        eraserSize = findViewById(R.id.ink_eraser_size)
    }

    private fun setUpToolbar() {
        toolPen.setOnClickListener { selectTool(InkView.Tool.PEN) }
        toolEraser.setOnClickListener { selectTool(InkView.Tool.ERASER) }
        toolSelect.setOnClickListener { selectTool(InkView.Tool.SELECT) }
        toolFill.setOnClickListener { selectTool(InkView.Tool.FILL) }
        // Undo and redo can change which strokes exist, and a selection belongs to the page it was marked on.
        undoButton.setOnClickListener { editor?.undo(); inkView.clearSelection(); afterEdit() }
        redoButton.setOnClickListener { editor?.redo(); inkView.clearSelection(); afterEdit() }
        prevButton.setOnClickListener { editor?.let { it.goToPage(it.pageIndex - 1) }; afterPageChange() }
        nextButton.setOnClickListener { editor?.let { it.goToPage(it.pageIndex + 1) }; afterPageChange() }
        findViewById<View>(R.id.ink_add_page).setOnClickListener { addPage() }
        findViewById<View>(R.id.ink_zoom_in).setOnClickListener { inkView.zoomStep(up = true) }
        findViewById<View>(R.id.ink_zoom_out).setOnClickListener { inkView.zoomStep(up = false) }
        zoomLevel.setOnClickListener { inkView.resetZoom() }
        showZoom()
        setUpSelectionTools()
        convertButton.setOnClickListener { convertSelection() }
        titleButton.setOnClickListener { markSelection(InkMark.Kind.TITLE) }
        tagButton.setOnClickListener { markSelection(InkMark.Kind.TAG) }
        findViewById<View>(R.id.ink_dictate).setOnClickListener { Dictation.start(this) { showRecognized(it, R.string.stt_dictated_title) } }
        chatButton.setOnClickListener { chatAboutSelection() }

        // One colour for the pen and the bucket; picking it keeps the tool it was picked for.
        for (view in listOf(colorView, fillColorView)) {
            view.setOnClickListener {
                ColorPalette.show(inkView, inkView.penColor) { color ->
                    inkView.penColor = color
                    updateControls()
                }
            }
        }
        eraseAreaButton.setOnClickListener { inkView.eraseWholeStrokes = false; updateControls() }
        eraseStrokesButton.setOnClickListener { inkView.eraseWholeStrokes = true; updateControls() }
        // Any size, with a slider (docs/16-grosor.md).
        penSize.setOnSeekBarChangeListener(onSizeChange { inkView.penWidth = InkSizes.PEN.valueAt(it) })
        eraserSize.setOnSeekBarChangeListener(onSizeChange { inkView.eraserRadius = InkSizes.ERASER.valueAt(it) })

        // The last pen and eraser used, in any note.
        inkView.penColor = toolPreferences.getInt(PREF_PEN_COLOR, ColorPalette.defaultColor)
        inkView.penWidth = InkSizes.PEN.coerce(toolPreferences.getFloat(PREF_PEN_WIDTH, DEFAULT_PEN_WIDTH))
        inkView.eraseWholeStrokes = toolPreferences.getBoolean(PREF_ERASE_STROKES, false)
        inkView.eraserRadius = InkSizes.ERASER.coerce(toolPreferences.getFloat(PREF_ERASER_RADIUS, DEFAULT_ERASER_RADIUS))
        penSize.progress = InkSizes.PEN.progressOf(inkView.penWidth)
        eraserSize.progress = InkSizes.ERASER.progressOf(inkView.eraserRadius)
        selectTool(InkView.Tool.PEN)
    }

    /** Follows a size slider as it moves: [apply] gets its position, then the samples are redrawn. */
    private fun onSizeChange(apply: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            apply(progress)
            showSizes()
        }

        override fun onStartTrackingTouch(bar: SeekBar) = Unit

        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }

    /** The pen and eraser samples and numbers, at their size on screen. */
    private fun showSizes() {
        val density = resources.displayMetrics.density
        penPreview.apply {
            color = inkView.penColor
            diameter = inkView.penWidth * density
        }
        setTextIfChanged(penSizeLabel, sizeLabel(inkView.penWidth))
        eraserPreview.apply {
            outline = true
            diameter = 2 * inkView.eraserRadius * density
        }
        setTextIfChanged(eraserSizeLabel, sizeLabel(inkView.eraserRadius))
    }

    private fun sizeLabel(size: Float) = String.format(Locale.getDefault(), if (size < 10f) "%.1f" else "%.0f", size)

    private fun selectTool(tool: InkView.Tool) {
        if (tool != InkView.Tool.SELECT) inkView.clearSelection()
        inkView.tool = tool
        updateControls()
    }

    /**
     * A page like the current one; but when the tablet is held the other way round from it, the choice between an
     * upright and a landscape page (docs/10-orientacion.md).
     */
    private fun addPage() {
        val editor = editor ?: return
        val page = editor.page
        val screenLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (screenLandscape == (page.width > page.height)) {
            editor.addPage()
            afterPageChange()
            return
        }
        val short = minOf(page.width, page.height)
        val long = maxOf(page.width, page.height)
        AlertDialog.Builder(this)
            .setTitle(R.string.ink_add_page)
            .setItems(arrayOf(getString(R.string.ink_page_upright), getString(R.string.ink_page_landscape))) { _, which ->
                if (which == 0) editor.addPage(width = short, height = long) else editor.addPage(width = long, height = short)
                afterPageChange()
            }
            .show()
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
        zoomLevel.text = getString(R.string.ink_zoom_level, label)
    }

    private fun afterEdit() {
        inkView.refreshAfter()
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
        toolPen.isSelected = inkView.tool == InkView.Tool.PEN
        toolEraser.isSelected = inkView.tool == InkView.Tool.ERASER
        toolSelect.isSelected = inkView.tool == InkView.Tool.SELECT
        toolFill.isSelected = inkView.tool == InkView.Tool.FILL
        undoButton.isEnabled = editor?.canUndo == true
        redoButton.isEnabled = editor?.canRedo == true
        prevButton.isEnabled = editor != null && editor.pageIndex > 0
        nextButton.isEnabled = editor != null && editor.pageIndex < editor.pageCount - 1
        setTextIfChanged(pageLabel, editor?.let { getString(R.string.ink_page, it.pageIndex + 1, it.pageCount) }.orEmpty())
        penOptions.visibility = visibleIf(inkView.tool == InkView.Tool.PEN)
        eraserOptions.visibility = visibleIf(inkView.tool == InkView.Tool.ERASER)
        selectOptions.visibility = visibleIf(inkView.tool == InkView.Tool.SELECT)
        fillOptions.visibility = visibleIf(inkView.tool == InkView.Tool.FILL)
        val selected = inkView.selection != null
        val holdsInk = inkView.selectedStrokes.isNotEmpty()
        selectHint.visibility = if (holdsInk) View.GONE else View.VISIBLE
        selectionEdit.visibility = visibleIf(holdsInk)
        pasteButton.visibility = visibleIf(!InkClipboard.isEmpty)
        convertButton.isEnabled = selected
        chatButton.isEnabled = selected
        titleButton.isEnabled = holdsInk
        tagButton.isEnabled = holdsInk
        selectedStrokes().firstOrNull()?.let { first ->
            // The swatches are rebuilt only when their colour changes: this runs after every stroke.
            if (first.color != selectionSwatch) {
                selectionSwatch = first.color
                selectionColor.background = ColorPalette.swatchDrawable(first.color, false, resources.displayMetrics.density)
            }
        }
        val penColor = inkView.penColor
        if (penColor != penSwatch) {
            penSwatch = penColor
            colorView.background = ColorPalette.swatchDrawable(penColor, false, resources.displayMetrics.density)
            fillColorView.background = ColorPalette.swatchDrawable(penColor, false, resources.displayMetrics.density)
        }
        eraseAreaButton.isSelected = !inkView.eraseWholeStrokes
        eraseStrokesButton.isSelected = inkView.eraseWholeStrokes
        showSizes()
    }

    /** Setting a text always lays it out again, even if it is the same. */
    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    private fun selectedStrokes(): List<Stroke> {
        val page = editor?.page ?: return emptyList()
        return inkView.selectedStrokes.mapNotNull { page.strokes.getOrNull(it) }
    }

    private fun setUpSelectionTools() {
        selectionColor.setOnClickListener {
            val current = selectedStrokes().firstOrNull()?.color ?: inkView.penColor
            ColorPalette.show(selectionColor, current) { color ->
                editor?.restyleStrokes(inkView.selectedStrokes, color = color)
                afterSelectionEdit()
            }
        }
        findViewById<View>(R.id.ink_selection_width).setOnClickListener { chooseSelectionWidth() }
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
        pasteButton.setOnClickListener {
            val editor = editor ?: return@setOnClickListener
            // The pasted strokes come selected, ready to be moved where they belong.
            val pasted = editor.insertStrokes(InkClipboard.placedOn(editor.page))
            inkView.selectStrokes(pasted)
            afterSelectionEdit()
        }
    }

    /**
     * The thickness of the selected strokes, with the pen's slider (docs/16-grosor.md); it starts at the first one's.
     * Fills keep theirs: their bands are as thick as they need to be to cover the shape.
     */
    private fun chooseSelectionWidth() {
        val page = editor?.page ?: return
        val strokes = inkView.selectedStrokes.filter { page.strokes.getOrNull(it)?.fill == false }
        val first = page.strokes.getOrNull(strokes.firstOrNull() ?: return) ?: return
        val density = resources.displayMetrics.density
        var width = InkSizes.PEN.coerce(first.width)
        val preview = SizePreview(this, null).apply { color = first.color }
        val label = TextView(this)
        val show = {
            // As it looks now, at this zoom.
            preview.diameter = width * inkView.zoom * density
            label.text = sizeLabel(width)
        }
        val bar = SeekBar(this).apply {
            max = InkSizes.STEPS
            progress = InkSizes.PEN.progressOf(width)
            setOnSeekBarChangeListener(onSizeChange { width = InkSizes.PEN.valueAt(it); show() })
        }
        show()
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(preview, LinearLayout.LayoutParams((44 * density).toInt(), (40 * density).toInt()))
            addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label, LinearLayout.LayoutParams((40 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ink_selection_width)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                editor?.restyleStrokes(strokes, width = width)
                afterSelectionEdit()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** After changing the selected strokes: redraw them, keep them selected (the rectangle may have grown) and save soon. */
    private fun afterSelectionEdit() {
        inkView.refreshAfter()
        inkView.selectStrokes(inkView.selectedStrokes)
        updateControls()
        scheduleAutosave()
    }

    /** What to render for the model: the page, which is immutable, and the selected area of it, taken now. */
    private class SelectionToRender(val page: InkPage, val area: InkRect) {
        /** The selection as a PNG: heavy, so only from a background thread. */
        fun image(): ByteArray = InkRenderer.renderSelection(page, area)
    }

    /** The selection to turn into an image; null (after saying why) if there is none or it holds no ink. */
    private fun selectionToRender(): SelectionToRender? {
        val editor = editor ?: return null
        val area = inkView.selection ?: return null
        val page = editor.page
        if (!area.hasInk(page)) {
            Toast.makeText(this, R.string.ink_selection_empty, Toast.LENGTH_SHORT).show()
            return null
        }
        return SelectionToRender(page, area)
    }

    private fun signedIn(): Boolean {
        val signedIn = container.chatGptSessions.load() != null
        if (!signedIn) AiMessages.showSignInNeeded(this)
        return signedIn
    }

    private fun convertSelection() = readSelection { text ->
        if (text.isBlank()) Toast.makeText(this, R.string.ai_convert_nothing, Toast.LENGTH_LONG).show() else showRecognized(text, R.string.ai_convert_title)
    }

    /**
     * Marks the selected strokes as a title or a tag (docs/13-indice.md): the AI reads them, the user checks the
     * text, and the mark goes into the page as one undo step.
     */
    private fun markSelection(kind: InkMark.Kind) = readSelection { read ->
        val firstLine = read.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val proposed = if (kind == InkMark.Kind.TAG) InkMark.tagText(firstLine) else firstLine
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            setText(proposed)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(if (kind == InkMark.Kind.TITLE) R.string.ink_mark_title else R.string.ink_mark_tag)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().let { if (kind == InkMark.Kind.TAG) InkMark.tagText(it) else it.trim() }
                if (text.isNotEmpty()) {
                    editor?.addMark(kind, text, inkView.selectedStrokes)
                    afterSelectionEdit()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * A new text note with everything written here, in clean Markdown (docs/14-pasar-a-limpio.md). Pages already read
     * for search are not sent again; the rest are read now, and kept for search too. Cancelling creates nothing.
     */
    private fun cleanCopy() {
        if (!signedIn()) return
        save()
        val current = note ?: return Toast.makeText(this, R.string.ink_draw_first, Toast.LENGTH_SHORT).show()
        val title = current.path.baseName
        val handle = TurnHandle()
        @Suppress("DEPRECATION") // ProgressDialog is the progress dialog API 19 has.
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.ink_clean_ordering))
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            setOnCancelListener { handle.cancel() }
            show()
        }
        val tell = { text: String -> handler.post { if (progress.isShowing) progress.setMessage(text) } }
        val date = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date())
        container.aiBackground.execute({
            val pages = current.document.pages
            val stored = container.transcripts.read(current)
            val texts = pages.mapIndexed { i, page ->
                val hash = InkTranscripts.hashOf(page)
                when {
                    page.isEmpty -> ""
                    stored.getOrNull(i)?.hash == hash -> stored[i].text
                    else -> {
                        tell(getString(R.string.ink_clean_reading, i + 1, pages.size))
                        val image = InkRenderer.renderSelection(page, InkRect(0f, 0f, page.width.toFloat(), page.height.toFloat()))
                        container.ai.convertToText(image, handle).also { container.transcripts.save(current, i, hash, it) }
                    }
                }
            }.filter { it.isNotBlank() }
            if (texts.isEmpty()) return@execute null
            val body = if (texts.size == 1) {
                texts.single()
            } else {
                tell(getString(R.string.ink_clean_ordering))
                container.ai.cleanCopy(texts, InkOutline.of(current.document).titles.map { it.first }, handle)
            }
            if (handle.isCancelled) return@execute null
            val content = "# ${getString(R.string.ink_clean_title, title)}\n\n$body\n\n${getString(R.string.ink_clean_footer, title, date)}\n"
            container.noteService.create(content, current.path.category).path
        }) { result ->
            if (isDestroyed) return@execute
            progress.dismiss()
            if (handle.isCancelled) return@execute
            result
                .onSuccess { path ->
                    if (path == null) Toast.makeText(this, R.string.ink_clean_nothing, Toast.LENGTH_LONG).show()
                    else startActivity(NoteEditorActivity.intent(this, path))
                }
                .onFailure { Toast.makeText(this, AiMessages.failure(this, it), Toast.LENGTH_LONG).show() }
        }
    }

    /** The titles of the note, page by page; picking one goes to its page. */
    private fun showIndex() {
        val editor = editor ?: return
        val entries = editor.document.pages.flatMapIndexed { i, page ->
            page.marks.filter { it.kind == InkMark.Kind.TITLE }.sortedBy { page.boundsOf(it)?.top ?: 0f }.map { it.text to i }
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.ink_index_empty, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_index)
            .setItems(entries.map { (title, page) -> getString(R.string.ink_index_entry, title, page + 1) }.toTypedArray()) { _, which ->
                editor.goToPage(entries[which].second)
                afterPageChange()
            }
            .show()
    }

    /** Has the AI read the selected handwriting, then hands the text to [onText]; a cancellable wait meanwhile. */
    private fun readSelection(onText: (String) -> Unit) {
        if (!signedIn()) return
        val selection = selectionToRender() ?: return
        val handle = TurnHandle()
        @Suppress("DEPRECATION") // ProgressDialog is the progress dialog API 19 has.
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.ai_converting))
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            setOnCancelListener { handle.cancel() }
            show()
        }
        container.aiBackground.execute({ container.ai.convertToText(selection.image(), handle) }) { result ->
            if (isDestroyed) return@execute
            progress.dismiss()
            if (handle.isCancelled) return@execute
            result
                .onSuccess(onText)
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
        val selection = selectionToRender() ?: return
        save()
        val file = File(cacheDir, AiChatActivity.SELECTION_FILE)
        // Rendering and writing the image take a moment: the chat opens once the file is there.
        container.aiBackground.execute({ file.writeBytes(selection.image()) }) { result ->
            if (isDestroyed) return@execute
            result
                .onSuccess { startActivity(AiChatActivity.intent(this, file, note?.path?.category ?: category)) }
                .onFailure { Toast.makeText(this, R.string.error_saving, Toast.LENGTH_LONG).show() }
        }
    }

    private fun saveToolPreferences() {
        toolPreferences.edit()
            .putInt(PREF_PEN_COLOR, inkView.penColor)
            .putFloat(PREF_PEN_WIDTH, inkView.penWidth)
            .putBoolean(PREF_ERASE_STROKES, inkView.eraseWholeStrokes)
            .putFloat(PREF_ERASER_RADIUS, inkView.eraserRadius)
            .apply()
    }

    /** The autosave: the first save of a new note is done now, later ones in the background. */
    private fun autosave() {
        val editor = editor ?: return
        if (deleted || destroyed) return
        val current = note
        if (current == null) return save()
        // The running save schedules the next one when it ends, if there is anything left to save.
        if (!editor.isModified || pending != null) return
        val snapshot = editor.document
        val task = FutureTask(Callable { container.inkNotes.save(current, snapshot) })
        val running = PendingSave(snapshot, task)
        pending = running
        saver.execute {
            task.run()
            handler.post {
                // Not if another save took it over meanwhile, or the screen is gone.
                if (pending === running && !destroyed && awaitPending()) scheduleAutosave()
            }
        }
    }

    /**
     * Waits for the background save, if any, and takes its result: the note, and the document it saved as saved.
     * Returns false if there was none or it failed.
     */
    private fun awaitPending(): Boolean {
        val running = pending ?: return false
        pending = null
        try {
            note = running.result.get()
            editor?.markSaved(running.snapshot)
            updateTitle()
            return true
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause !is NoteStoreException) throw cause ?: e
            Toast.makeText(this, R.string.error_saving, Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun save() {
        awaitPending()
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
                awaitPending()
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
        private const val EXTRA_PAGE = "page"
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
        private const val DEFAULT_PEN_WIDTH = 3.5f
        private const val DEFAULT_ERASER_RADIUS = 10f

        /** Contacts larger than this are a palm. Calibrated with the AmberVec stylus (see docs/06). */
        private const val PALM_TOUCH_MAJOR = 0f

        /** Opens the handwritten note at [path], at page [page] (0-based). */
        fun intent(context: Context, path: NotePath, page: Int = 0): Intent =
            Intent(context, InkNoteActivity::class.java).putExtra(EXTRA_PATH, path.value).putExtra(EXTRA_PAGE, page)

        fun intentForNew(context: Context, category: String): Intent =
            Intent(context, InkNoteActivity::class.java).putExtra(EXTRA_CATEGORY, category)
    }
}
