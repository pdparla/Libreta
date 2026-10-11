package com.parla.libreta.ui.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.parla.libreta.BuildConfig
import com.parla.libreta.core.ink.InkEditor
import com.parla.libreta.core.ink.InkPoint
import com.parla.libreta.core.ink.InkRect
import com.parla.libreta.core.ink.Stroke
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Writing surface for one page of an [InkEditor].
 *
 * The screen is capacitive, so the stylus is just a small contact: the first contact that is not too large
 * (see [maxTouchMajor]) draws, and every other one (the palm, a resting finger) is ignored. Finished strokes live
 * in a cached bitmap together with the paper; only the stroke being drawn is redrawn on every frame.
 *
 * With [Tool.SELECT], dragging marks a rectangle ([selection]) instead of drawing. It takes the strokes with ink
 * inside ([selectedStrokes]) and snaps around them; then dragging inside it moves them and dragging its corner
 * handle resizes them. While they move they are drawn on their own, transformed by the canvas, over a cache
 * without them; the change goes into the document, as one undo step, when the finger lifts.
 */
class InkView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Tool { PEN, ERASER, SELECT }

    var editor: InkEditor? = null
        set(value) {
            field = value
            refresh()
        }

    var tool = Tool.PEN
    var penColor = 0xff202124.toInt()
    var penWidth = 3f

    /** Whether the eraser removes whole strokes or only the ink under it. */
    var eraseWholeStrokes = false

    /** Eraser radius, in page units. */
    var eraserRadius = 10f

    /**
     * The rectangle marked with [Tool.SELECT] on the current page, in page units: around [selectedStrokes] when it
     * holds ink, as drawn otherwise; null when there is none.
     */
    var selection: InkRect? = null
        private set

    /** Indexes into the current page's strokes of the ink the selection holds; empty when it holds none. */
    var selectedStrokes: List<Int> = emptyList()
        private set

    /** Called when the selection changes through touch: marked, moved, resized or dropped. */
    var onSelectionChange: () -> Unit = {}

    /** Contacts larger than this (touch major, in pixels) are a palm, not the stylus. 0 accepts every contact. */
    var maxTouchMajor = 0f

    /** Called after every change to the document. */
    var onChange: () -> Unit = {}

    /** Called when a finger or the stylus touches the page, before anything is drawn. */
    var onTouchStart: () -> Unit = {}

    /** Called when the last finger leaves the page. */
    var onTouchEnd: () -> Unit = {}

    /** Called with the view size in page units (dp) once it is laid out, to size new documents. */
    var onSizeKnown: (width: Int, height: Int) -> Unit = { _, _ -> }

    private var cache: Bitmap? = null
    private var scale = 1f
    private var offsetX = 0f
    private val outsidePage = 0xffdedede.toInt()

    private var activePointer = NO_POINTER
    private val points = ArrayList<InkPoint>()
    private var downTime = 0L
    private var startedAt = 0L
    private var livePath: Path? = null
    private var livePaint: Paint? = null
    private var eraserX = Float.NaN
    private var lastErasedX = Float.NaN
    private var lastErasedY = 0f
    private var eraserY = 0f
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xff5f6368.toInt()
        strokeWidth = resources.displayMetrics.density
    }
    private enum class Drag { NONE, MARK, MOVE, RESIZE }

    private var drag = Drag.NONE
    private var dragFromX = 0f
    private var dragFromY = 0f
    private var dragDx = 0f
    private var dragDy = 0f
    private var dragScale = 1f
    private var dragPaths: List<Pair<Path, Paint>> = emptyList()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff1a73e8.toInt() }
    private val handleSize = 14 * resources.displayMetrics.density
    private val handleReach = 32 * resources.displayMetrics.density
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xff1a73e8.toInt()
        strokeWidth = 2 * resources.displayMetrics.density
        val dash = 6 * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
    }
    private val selectionFill = Paint().apply { color = 0x181a73e8 }

    fun clearSelection() {
        selection = null
        selectedStrokes = emptyList()
        invalidate()
    }

    /** Selects the strokes at [indexes] of the current page, with the rectangle around them. */
    fun selectStrokes(indexes: List<Int>) {
        val page = editor?.page ?: return
        val valid = indexes.filter { it in page.strokes.indices }
        selectedStrokes = valid
        selection = InkRect.around(valid.map { page.strokes[it] })
        invalidate()
    }

    /** Redraws everything: after undo, redo, erasing, a page change or a new document. */
    fun refresh() = refresh(hidden = emptySet())

    /** Redraws the page into the cache, leaving out the strokes at [hidden] (the ones being moved). */
    private fun refresh(hidden: Set<Int>) {
        val page = editor?.page ?: return
        if (width == 0 || height == 0) return
        scale = min(width / page.width.toFloat(), height / page.height.toFloat())
        offsetX = (width - page.width * scale) / 2
        val bitmap = cache?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                cache?.recycle()
                cache = it
            }
        val canvas = Canvas(bitmap)
        canvas.drawColor(outsidePage)
        canvas.save()
        canvas.translate(offsetX, 0f)
        canvas.clipRect(0f, 0f, page.width * scale, page.height * scale)
        InkRenderer.drawPaper(canvas, page, scale)
        page.strokes.forEachIndexed { i, stroke -> if (i !in hidden) InkRenderer.drawStroke(canvas, stroke, scale) }
        canvas.restore()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        onSizeKnown((w / density).toInt(), (h / density).toInt())
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache?.recycle()
        cache = null
    }

    override fun onDraw(canvas: Canvas) {
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (!eraserX.isNaN()) canvas.drawCircle(eraserX, eraserY, eraserRadius * scale, eraserPaint)
        selection?.let { area -> drawSelection(canvas, area) }
        val path = livePath ?: return
        canvas.save()
        canvas.translate(offsetX, 0f)
        canvas.drawPath(path, livePaint!!)
        canvas.restore()
    }

    private fun drawSelection(canvas: Canvas, area: InkRect) {
        val transforming = drag == Drag.MOVE || drag == Drag.RESIZE
        if (transforming && dragPaths.isNotEmpty()) {
            canvas.save()
            canvas.translate(offsetX + dragDx * scale, dragDy * scale)
            canvas.scale(dragScale, dragScale, area.left * scale, area.top * scale)
            dragPaths.forEach { (path, paint) -> canvas.drawPath(path, paint) }
            canvas.restore()
        }
        val shown = if (transforming) area.transformed(dragDx, dragDy, dragScale) else area
        val left = offsetX + shown.left * scale
        val right = offsetX + shown.right * scale
        canvas.drawRect(left, shown.top * scale, right, shown.bottom * scale, selectionFill)
        canvas.drawRect(left, shown.top * scale, right, shown.bottom * scale, selectionPaint)
        if (selectedStrokes.isNotEmpty()) {
            val half = handleSize / 2
            canvas.drawRect(right - half, shown.bottom * scale - half, right + half, shown.bottom * scale + half, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val editor = editor ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                onTouchStart()
                val index = event.actionIndex
                logContact(event, index)
                if (activePointer == NO_POINTER && !isPalm(event, index)) {
                    activePointer = event.getPointerId(index)
                    begin(editor, event, index)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) move(editor, event, index)
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == activePointer) end(editor)
            MotionEvent.ACTION_UP -> {
                if (activePointer != NO_POINTER) end(editor)
                performClick()
                onTouchEnd()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancel()
                onTouchEnd()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun isPalm(event: MotionEvent, index: Int) = maxTouchMajor > 0 && event.getTouchMajor(index) > maxTouchMajor

    private fun begin(editor: InkEditor, event: MotionEvent, index: Int) {
        downTime = event.eventTime
        startedAt = System.currentTimeMillis()
        points.clear()
        when (tool) {
            Tool.PEN -> {
                livePaint = InkRenderer.strokePaint(penColor, penWidth * scale)
                addPoint(event.getX(index), event.getY(index), event.eventTime)
            }
            Tool.ERASER -> {
                // The whole rub is one undo step.
                editor.beginGesture()
                lastErasedX = Float.NaN
                eraseAlong(editor, event, index)
            }
            Tool.SELECT -> beginSelect(editor, event.getX(index), event.getY(index))
        }
    }

    /** On the handle: resize. Inside the selection: move. Anywhere else: mark a new rectangle. */
    private fun beginSelect(editor: InkEditor, viewX: Float, viewY: Float) {
        dragFromX = (viewX - offsetX) / scale
        dragFromY = viewY / scale
        dragDx = 0f
        dragDy = 0f
        dragScale = 1f
        val area = selection
        val margin = handleReach / 2 / scale
        drag = when {
            area == null || selectedStrokes.isEmpty() -> Drag.MARK
            hypot(viewX - (offsetX + area.right * scale), viewY - area.bottom * scale) <= handleReach -> Drag.RESIZE
            InkRect(area.left - margin, area.top - margin, area.right + margin, area.bottom + margin).contains(dragFromX, dragFromY) -> Drag.MOVE
            else -> Drag.MARK
        }
        if (drag == Drag.MARK) {
            clearSelection()
            return
        }
        val page = editor.page
        dragPaths = selectedStrokes.map { InkRenderer.strokePath(page.strokes[it], scale) }
        refresh(hidden = selectedStrokes.toSet())
    }

    private fun move(editor: InkEditor, event: MotionEvent, index: Int) {
        if (tool == Tool.ERASER) return eraseAlong(editor, event, index)
        if (tool == Tool.SELECT) return select(editor, event, index)
        for (h in 0 until event.historySize) {
            addPoint(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h))
        }
        addPoint(event.getX(index), event.getY(index), event.eventTime)
    }

    private fun end(editor: InkEditor) {
        activePointer = NO_POINTER
        if (tool == Tool.ERASER) {
            editor.endGesture()
            eraserX = Float.NaN
            onChange()
        }
        if (tool == Tool.SELECT) endSelect(editor)
        if (tool == Tool.PEN && points.isNotEmpty()) {
            val stroke = Stroke(penColor, penWidth, startedAt, ArrayList(points))
            editor.addStroke(stroke)
            // Draw just the new stroke into the cache instead of redrawing the page.
            cache?.let { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.translate(offsetX, 0f)
                InkRenderer.drawStroke(canvas, stroke, scale)
            }
            onChange()
        }
        points.clear()
        livePath = null
        invalidate()
    }

    private fun cancel() {
        activePointer = NO_POINTER
        if (tool == Tool.SELECT) {
            // A move or resize that was interrupted is dropped; a rectangle being marked too.
            if (drag == Drag.MOVE || drag == Drag.RESIZE) finishTransform() else clearSelection()
            drag = Drag.NONE
            onSelectionChange()
        }
        editor?.endGesture()
        eraserX = Float.NaN
        points.clear()
        livePath = null
        invalidate()
    }

    private fun addPoint(viewX: Float, viewY: Float, eventTime: Long) {
        val page = editor?.page ?: return
        val x = ((viewX - offsetX) / scale).coerceIn(0f, page.width.toFloat())
        val y = (viewY / scale).coerceIn(0f, page.height.toFloat())
        val last = points.lastOrNull()
        // Skip samples closer than a fraction of a unit: they add size, not shape.
        if (last != null && hypot(x - last.x, y - last.y) < MIN_DISTANCE) return
        points += InkPoint(x, y, (eventTime - downTime).toInt())
        livePath = InkRenderer.pathOf(points, scale)
        invalidate()
    }

    private fun select(editor: InkEditor, event: MotionEvent, index: Int) {
        val x = (event.getX(index) - offsetX) / scale
        val y = event.getY(index) / scale
        val page = editor.page
        val area = selection
        when (drag) {
            Drag.MARK -> selection = InkRect.between(dragFromX, dragFromY, x, y, page)
            Drag.MOVE -> area?.moveWithin(x - dragFromX, y - dragFromY, page)?.let { (dx, dy) ->
                dragDx = dx
                dragDy = dy
            }
            Drag.RESIZE -> if (area != null && area.width > 0f && area.height > 0f) {
                // Proportional, around the top-left corner: the handle follows whichever side was pulled further.
                val wanted = max((x - area.left) / area.width, (y - area.top) / area.height)
                dragScale = area.scaleWithin(wanted, page)
            }
            Drag.NONE -> return
        }
        invalidate()
    }

    /** The finger lifted: keep the marked rectangle, or put the moved or resized strokes into the document. */
    private fun endSelect(editor: InkEditor) {
        val area = selection
        when (drag) {
            Drag.MARK -> {
                val page = editor.page
                val strokes = area?.takeIf { it.isUsable }?.selectedStrokes(page).orEmpty()
                when {
                    strokes.isNotEmpty() -> selectStrokes(strokes)
                    area?.isUsable == true -> selectedStrokes = emptyList()
                    else -> clearSelection()
                }
            }
            Drag.MOVE, Drag.RESIZE -> if (area != null) {
                editor.transformStrokes(selectedStrokes, dragDx, dragDy, dragScale, area.left, area.top)
                finishTransform()
                selectStrokes(selectedStrokes)
                onChange()
            }
            Drag.NONE -> Unit
        }
        drag = Drag.NONE
        onSelectionChange()
    }

    private fun finishTransform() {
        drag = Drag.NONE
        dragPaths = emptyList()
        dragDx = 0f
        dragDy = 0f
        dragScale = 1f
        refresh()
    }

    /** Erases at every sample of the event, then redraws the page once. */
    private fun eraseAlong(editor: InkEditor, event: MotionEvent, index: Int) {
        var erased = false
        for (h in 0 until event.historySize) erased = erase(editor, event.getHistoricalX(index, h), event.getHistoricalY(index, h)) || erased
        erased = erase(editor, event.getX(index), event.getY(index)) || erased
        eraserX = event.getX(index)
        eraserY = event.getY(index)
        if (erased) refresh() else invalidate()
    }

    /** Erases from the previous sample to this one, in steps of half the eraser so a quick rub leaves no gaps. */
    private fun erase(editor: InkEditor, viewX: Float, viewY: Float): Boolean {
        val x = (viewX - offsetX) / scale
        val y = viewY / scale
        val fromX = if (lastErasedX.isNaN()) x else lastErasedX
        val fromY = if (lastErasedX.isNaN()) y else lastErasedY
        val steps = max(1, ceil(hypot(x - fromX, y - fromY) / (eraserRadius / 2)).toInt())
        var erased = false
        for (i in 1..steps) {
            val px = fromX + (x - fromX) * i / steps
            val py = fromY + (y - fromY) * i / steps
            erased = (if (eraseWholeStrokes) editor.eraseStrokesAt(px, py, eraserRadius) else editor.eraseAreaAt(px, py, eraserRadius)) || erased
        }
        lastErasedX = x
        lastErasedY = y
        return erased
    }

    /** Debug builds log every contact, to calibrate [maxTouchMajor] for the stylus and the palm. */
    private fun logContact(event: MotionEvent, index: Int) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "contact major=${event.getTouchMajor(index)} minor=${event.getTouchMinor(index)} " +
                "size=${event.getSize(index)} pressure=${event.getPressure(index)} pointers=${event.pointerCount}",
        )
    }

    private companion object {
        const val TAG = "LibretaInk"
        const val NO_POINTER = -1
        const val MIN_DISTANCE = 0.75f
    }
}
