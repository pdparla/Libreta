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
import com.parla.libreta.core.ink.InkDiff
import com.parla.libreta.core.ink.InkEditor
import com.parla.libreta.core.ink.InkMark
import com.parla.libreta.core.ink.InkPage
import com.parla.libreta.core.ink.InkPoint
import com.parla.libreta.core.ink.InkRect
import com.parla.libreta.core.ink.InkViewport
import com.parla.libreta.core.ink.Stroke
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Writing surface for one page of an [InkEditor].
 *
 * The screen is capacitive, so the stylus is just a small contact: the first contact that is not too large
 * (see [maxTouchMajor]) draws, and every other one (the palm, a resting finger) is ignored. Finished strokes live
 * in a cached bitmap together with the paper; only the stroke being drawn is redrawn on every frame.
 *
 * The page can be zoomed from 1× to 4× and moved (docs/09-zoom.md): with two fingers, or with [zoomStep]. Every
 * conversion between the screen and the page goes through an [InkViewport]. A second finger that arrives while the
 * first contact is still fresh turns both into a pinch and drops what the first had started; while pinching, the
 * cached page is only scaled and moved by the canvas, and redrawn sharp when the fingers lift.
 *
 * With [Tool.SELECT], dragging marks a rectangle ([selection]) instead of drawing. It takes the strokes with ink
 * inside ([selectedStrokes]) and snaps around them; then dragging inside it moves them and dragging its corner
 * handle resizes them. While they move they are drawn on their own, transformed by the canvas, over a cache
 * without them; the change goes into the document, as one undo step, when the finger lifts.
 *
 * With [Tool.FILL], a tap fills the shape under it with [penColor] (docs/15-relleno.md).
 */
class InkView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Tool { PEN, ERASER, SELECT, FILL }

    var editor: InkEditor? = null
        set(value) {
            field = value
            viewport = null
            drawn = null
            refresh()
        }

    var tool = Tool.PEN
    var penColor = 0xff202124.toInt()

    /** Pen thickness as it looks on screen; a stroke made at zoom z is stored z times thinner. */
    var penWidth = 3f

    /** Whether the eraser removes whole strokes or only the ink under it. */
    var eraseWholeStrokes = false

    /** Eraser radius as it looks on screen, in page units at 1×. */
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

    /** Called when a tap with [Tool.FILL] finds nothing to fill: it landed on the ink. */
    var onNothingToFill: () -> Unit = {}

    /** Called when the zoom changes, with the new level. */
    var onZoomChange: (Float) -> Unit = {}

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

    val zoom: Float get() = viewport?.zoom ?: 1f

    private var viewport: InkViewport? = null
    private val scale: Float get() = viewport?.scale ?: 1f
    private val originX: Float get() = viewport?.originX ?: 0f
    private val originY: Float get() = viewport?.originY ?: 0f

    private var cache: Bitmap? = null
    // What the cache shows: the page, its index, and the strokes of it left out (the ones being moved). Whatever an
    // edit changes since is what [refreshAfter] redraws.
    private var drawn: InkPage? = null
    private var drawnIndex = -1
    private var drawnHidden: List<Stroke> = emptyList()
    // Where the page was when the cache was drawn: while pinching, the cache is mapped from there to the viewport.
    private var renderedScale = 1f
    private var renderedOriginX = 0f
    private var renderedOriginY = 0f
    private val outsidePage = 0xffdedede.toInt()

    private var activePointer = NO_POINTER
    private val points = ArrayList<InkPoint>()
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var startedAt = 0L
    // The stroke being drawn, in view-scale units from the page origin: [livePath] is its smooth path up to the latest
    // midpoint, extended by one segment per sample; [liveTail] is the straight stretch from there to the latest point.
    private val livePath = Path()
    private val liveTail = Path()
    private var liveDrawing = false
    private var livePaint: Paint? = null
    // Reused by every redraw of the cache, which runs on the UI thread only.
    private val strokeBrush = InkRenderer.scratchPaint()
    private var eraserX = Float.NaN
    private var lastErasedX = Float.NaN
    private var lastErasedY = 0f
    private var eraserY = 0f
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xff5f6368.toInt()
        strokeWidth = resources.displayMetrics.density
    }

    // Two-finger zoom and pan, until every finger lifts.
    private var pinching = false
    private var pinchValid = false
    private var pinchStartDistance = 1f
    private var pinchStartZoom = 1f
    private var pinchMidX = 0f
    private var pinchMidY = 0f
    private val freshTravel = FRESH_TRAVEL_DP * resources.displayMetrics.density

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
    private val titlePaint = Paint().apply { color = 0x40FFC107 }
    private val tagPaint = Paint().apply { color = 0x3334A853 }

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

    /** One step of [InkViewport.STEPS] in or out, around the middle of the view. */
    fun zoomStep(up: Boolean) = zoomTo(InkViewport.step(zoom, up))

    fun resetZoom() = zoomTo(1f)

    private fun zoomTo(level: Float) {
        val viewport = viewport ?: return
        viewport.zoomAround(width / 2f, height / 2f, level)
        refresh()
        onZoomChange(viewport.zoom)
    }

    /** After changing page: the same zoom, at the top-left corner of the new page. */
    fun showPageStart() {
        val page = editor?.page
        val viewport = viewport
        if (page != null && viewport != null && (viewport.pageWidth != page.width || viewport.pageHeight != page.height)) {
            this.viewport = null
        } else {
            viewport?.showTopLeft()
        }
        refresh()
    }

    /** Redraws everything: after a page change, a zoom or a new document, or an edit that [refreshAfter] cannot follow. */
    fun refresh() = refresh(hidden = emptySet())

    /**
     * Redraws what the editor's page now has different from what the cache shows: after undo, redo, paste, delete,
     * recolouring, marking and the like, which would otherwise redraw the whole page. A full [refresh] when it is
     * another page, or the same page with another size or paper.
     */
    fun refreshAfter() {
        val editor = editor ?: return
        val page = editor.page
        val shown = drawn
        if (shown == null || cache == null || drawnIndex != editor.pageIndex || !InkDiff.sameCanvas(shown, page)) return refresh()
        val dirty = InkDiff.dirtyArea(shown, page, drawnHidden)
        if (dirty == null) {
            drawn = page
            invalidate()
        } else {
            refreshArea(dirty)
        }
    }

    /** Redraws the visible part of the page into the cache, leaving out the strokes at [hidden] (the ones being moved). */
    private fun refresh(hidden: Set<Int>) {
        val page = editor?.page ?: return
        if (width == 0 || height == 0) return
        val viewport = viewport?.takeIf { it.viewWidth == width && it.viewHeight == height && it.pageWidth == page.width && it.pageHeight == page.height }
            ?: InkViewport(page.width, page.height, width, height).also { viewport = it }
        val bitmap = cache?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                cache?.recycle()
                cache = it
            }
        val scale = viewport.scale
        val canvas = Canvas(bitmap)
        canvas.drawColor(outsidePage)
        canvas.save()
        canvas.translate(viewport.originX, viewport.originY)
        canvas.clipRect(0f, 0f, page.width * scale, page.height * scale)
        drawPage(canvas, page, scale, viewport.visible(), hidden)
        canvas.restore()
        renderedScale = scale
        renderedOriginX = viewport.originX
        renderedOriginY = viewport.originY
        shown(page, hidden)
        invalidate()
    }

    /** The cache now shows [page] without the strokes at [hidden]. */
    private fun shown(page: InkPage, hidden: Set<Int>) {
        drawn = page
        drawnIndex = editor?.pageIndex ?: -1
        drawnHidden = if (hidden.isEmpty()) emptyList() else hidden.mapNotNull { page.strokes.getOrNull(it) }
    }

    /**
     * Paper, marks and the strokes of [page] that touch [within] (page units), leaving out the ones at [hidden], on a
     * canvas already moved to the page origin and clipped to the part to draw. Shared by [refresh] and [refreshArea],
     * so that a partial redraw cannot differ from a full one.
     */
    private fun drawPage(canvas: Canvas, page: InkPage, scale: Float, within: InkRect, hidden: Set<Int>) {
        InkRenderer.drawPaper(canvas, page, scale)
        // Titles and tags (docs/13-indice.md) behind their ink, so the page shows what is marked.
        page.marks.forEach { mark ->
            page.boundsOf(mark)?.let { r ->
                val pad = InkDiff.MARK_PADDING
                canvas.drawRect((r.left - pad) * scale, (r.top - pad) * scale, (r.right + pad) * scale, (r.bottom + pad) * scale,
                    if (mark.kind == InkMark.Kind.TITLE) titlePaint else tagPaint)
            }
        }
        // Nothing hidden is the usual case: no lookup, which would box every index.
        val leaveOut = hidden.isNotEmpty()
        page.strokes.forEachIndexed { i, stroke ->
            if ((!leaveOut || i !in hidden) && stroke.bounds.intersects(within)) InkRenderer.drawStroke(canvas, stroke, scale, strokeBrush)
        }
    }

    /**
     * Redraws only [area] (page units) of the cache, leaving out the strokes at [hidden], for a change that stays
     * inside it: erasing would otherwise redraw the whole page on every move of the stylus. The cache then shows the
     * current page, so [area] must cover everything that differs from what it showed. Falls back to [refresh] when
     * there is no cache or it was drawn for another viewport.
     */
    private fun refreshArea(area: InkRect, hidden: Set<Int> = emptySet()) {
        val page = editor?.page ?: return
        val bitmap = cache?.takeIf { it.width == width && it.height == height }
        val viewport = viewport
        if (bitmap == null || viewport == null || viewport.scale != renderedScale ||
            viewport.originX != renderedOriginX || viewport.originY != renderedOriginY
        ) return refresh(hidden)
        val scale = renderedScale
        // A couple of pixels more on every side, for the antialiased edges of what was there; and no further than the
        // bitmap goes, so that zoomed in only what is on screen is drawn.
        val left = max(max(0f, -renderedOriginX), floor(area.left * scale) - AREA_SLACK)
        val top = max(max(0f, -renderedOriginY), floor(area.top * scale) - AREA_SLACK)
        val right = minOf(page.width * scale, width - renderedOriginX, ceil(area.right * scale) + AREA_SLACK)
        val bottom = minOf(page.height * scale, height - renderedOriginY, ceil(area.bottom * scale) + AREA_SLACK)
        shown(page, hidden)
        if (left >= right || top >= bottom) return invalidate()
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.translate(renderedOriginX, renderedOriginY)
        canvas.clipRect(left, top, right, bottom)
        drawPage(canvas, page, scale, InkRect(left / scale, top / scale, right / scale, bottom / scale), hidden)
        canvas.restore()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        onSizeKnown((w / density).toInt(), (h / density).toInt())
        // Turning the tablet mid-gesture: drop what was under way, as Android would with ACTION_CANCEL.
        if (pinching) endPinch() else if (activePointer != NO_POINTER) cancel()
        if (w > 0 && h > 0) viewport = viewport?.resized(w, h)
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache?.recycle()
        cache = null
    }

    override fun onDraw(canvas: Canvas) {
        cache?.let { bitmap ->
            if (scale == renderedScale && originX == renderedOriginX && originY == renderedOriginY) {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            } else {
                // Pinching: the page drawn at the start, moved and scaled to where it is now; sharp again on release.
                canvas.drawColor(outsidePage)
                val k = scale / renderedScale
                canvas.save()
                canvas.translate(originX - renderedOriginX * k, originY - renderedOriginY * k)
                canvas.scale(k, k)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                canvas.restore()
            }
        }
        if (!eraserX.isNaN()) canvas.drawCircle(eraserX, eraserY, eraserRadius / zoom * scale, eraserPaint)
        selection?.let { area -> drawSelection(canvas, area) }
        if (!liveDrawing) return
        canvas.save()
        canvas.translate(originX, originY)
        canvas.drawPath(livePath, livePaint!!)
        canvas.drawPath(liveTail, livePaint!!)
        canvas.restore()
    }

    private fun drawSelection(canvas: Canvas, area: InkRect) {
        val transforming = drag == Drag.MOVE || drag == Drag.RESIZE
        if (transforming && dragPaths.isNotEmpty()) {
            canvas.save()
            canvas.translate(originX + dragDx * scale, originY + dragDy * scale)
            canvas.scale(dragScale, dragScale, area.left * scale, area.top * scale)
            dragPaths.forEach { (path, paint) -> canvas.drawPath(path, paint) }
            canvas.restore()
        }
        val shown = if (transforming) area.transformed(dragDx, dragDy, dragScale) else area
        val left = originX + shown.left * scale
        val right = originX + shown.right * scale
        val top = originY + shown.top * scale
        val bottom = originY + shown.bottom * scale
        canvas.drawRect(left, top, right, bottom, selectionFill)
        canvas.drawRect(left, top, right, bottom, selectionPaint)
        if (selectedStrokes.isNotEmpty()) {
            val half = handleSize / 2
            canvas.drawRect(right - half, bottom - half, right + half, bottom + half, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val editor = editor ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchStart()
                logContact(event, 0)
                if (!isPalm(event, 0)) {
                    activePointer = event.getPointerId(0)
                    begin(editor, event, 0)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                logContact(event, event.actionIndex)
                when {
                    pinching -> startPinch(event)
                    activePointer != NO_POINTER && isFresh(event) -> {
                        abandon(editor)
                        pinching = true
                        startPinch(event)
                    }
                    // A hand resting while writing: ignored, as before zoom existed.
                    activePointer != NO_POINTER -> Unit
                    !isPalm(event, event.actionIndex) -> {
                        activePointer = event.getPointerId(event.actionIndex)
                        begin(editor, event, event.actionIndex)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching) {
                    pinch(event)
                    return true
                }
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) move(editor, event, index)
            }
            MotionEvent.ACTION_POINTER_UP -> when {
                // The rest of the pinch waits for a second finger again, or for every finger to lift.
                pinching -> pinchValid = false
                event.getPointerId(event.actionIndex) == activePointer -> end(editor)
            }
            MotionEvent.ACTION_UP -> {
                if (pinching) endPinch() else if (activePointer != NO_POINTER) end(editor)
                performClick()
                onTouchEnd()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pinching) endPinch() else cancel()
                onTouchEnd()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun isPalm(event: MotionEvent, index: Int) = maxTouchMajor > 0 && event.getTouchMajor(index) > maxTouchMajor

    /** Whether the active contact has only just started: a second finger now means a pinch, not a resting hand. */
    private fun isFresh(event: MotionEvent): Boolean {
        val index = event.findPointerIndex(activePointer)
        val travelled = if (index >= 0) hypot(event.getX(index) - downX, event.getY(index) - downY) else 0f
        return event.eventTime - downTime < FRESH_MILLIS || travelled < freshTravel
    }

    /** Drops what the first contact had started, leaving no trace and no undo step. */
    private fun abandon(editor: InkEditor) {
        activePointer = NO_POINTER
        when (tool) {
            Tool.PEN, Tool.FILL -> Unit
            Tool.ERASER -> {
                editor.cancelGesture()
                eraserX = Float.NaN
                refreshAfter()
            }
            Tool.SELECT -> {
                if (drag == Drag.MOVE || drag == Drag.RESIZE) finishTransform() else clearSelection()
                drag = Drag.NONE
                onSelectionChange()
            }
        }
        points.clear()
        clearLive()
        invalidate()
    }

    private fun startPinch(event: MotionEvent) {
        val active = (0 until event.pointerCount).filter { event.actionMasked != MotionEvent.ACTION_POINTER_UP || it != event.actionIndex }
        if (active.size < 2) return
        val a = active[0]
        val b = active[1]
        pinchStartDistance = max(1f, hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b)))
        pinchStartZoom = zoom
        pinchMidX = (event.getX(a) + event.getX(b)) / 2
        pinchMidY = (event.getY(a) + event.getY(b)) / 2
        pinchValid = true
    }

    /** The page follows the midpoint between the two fingers and scales with the distance between them. */
    private fun pinch(event: MotionEvent) {
        val viewport = viewport ?: return
        if (!pinchValid || event.pointerCount < 2) return
        val midX = (event.getX(0) + event.getX(1)) / 2
        val midY = (event.getY(0) + event.getY(1)) / 2
        val distance = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
        viewport.panBy(midX - pinchMidX, midY - pinchMidY)
        viewport.zoomAround(midX, midY, pinchStartZoom * distance / pinchStartDistance)
        pinchMidX = midX
        pinchMidY = midY
        invalidate()
    }

    private fun endPinch() {
        pinching = false
        pinchValid = false
        activePointer = NO_POINTER
        refresh()
        onZoomChange(zoom)
    }

    private fun begin(editor: InkEditor, event: MotionEvent, index: Int) {
        downTime = event.eventTime
        downX = event.getX(index)
        downY = event.getY(index)
        startedAt = System.currentTimeMillis()
        points.clear()
        when (tool) {
            Tool.PEN -> {
                livePaint = InkRenderer.strokePaint(penColor, penWidth / zoom * scale)
                addPoint(event.getX(index), event.getY(index), event.eventTime)
            }
            Tool.ERASER -> {
                // The whole rub is one undo step.
                editor.beginGesture()
                lastErasedX = Float.NaN
                eraseAlong(editor, event, index)
            }
            Tool.SELECT -> beginSelect(editor, event.getX(index), event.getY(index))
            Tool.FILL -> Unit
        }
    }

    /** On the handle: resize. Inside the selection: move. Anywhere else: mark a new rectangle. */
    private fun beginSelect(editor: InkEditor, viewX: Float, viewY: Float) {
        val viewport = viewport ?: return
        dragFromX = viewport.toPageX(viewX)
        dragFromY = viewport.toPageY(viewY)
        dragDx = 0f
        dragDy = 0f
        dragScale = 1f
        val area = selection
        val margin = handleReach / 2 / scale
        drag = when {
            area == null || selectedStrokes.isEmpty() -> Drag.MARK
            hypot(viewX - viewport.toViewX(area.right), viewY - viewport.toViewY(area.bottom)) <= handleReach -> Drag.RESIZE
            InkRect(area.left - margin, area.top - margin, area.right + margin, area.bottom + margin).contains(dragFromX, dragFromY) -> Drag.MOVE
            else -> Drag.MARK
        }
        if (drag == Drag.MARK) {
            clearSelection()
            return
        }
        val page = editor.page
        dragPaths = selectedStrokes.map { InkRenderer.strokePath(page.strokes[it], scale) }
        // Only where they are: the rest of the cache stays.
        val moving = selectedStrokes.map { page.strokes[it] }
        refreshArea(InkRect.around(moving) ?: return, hidden = selectedStrokes.toSet())
    }

    private fun move(editor: InkEditor, event: MotionEvent, index: Int) {
        if (tool == Tool.ERASER) return eraseAlong(editor, event, index)
        if (tool == Tool.SELECT) return select(editor, event, index)
        if (tool == Tool.FILL) return
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
        if (tool == Tool.FILL) fillAt(editor, downX, downY)
        if (tool == Tool.PEN && points.isNotEmpty()) {
            val stroke = Stroke(penColor, penWidth / zoom, startedAt, ArrayList(points))
            editor.addStroke(stroke)
            // Draw just the new stroke into the cache instead of redrawing the page.
            cache?.let { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.translate(renderedOriginX, renderedOriginY)
                InkRenderer.drawStroke(canvas, stroke, renderedScale, strokeBrush)
                shown(editor.page, emptySet())
            }
            onChange()
        }
        points.clear()
        clearLive()
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
        clearLive()
        invalidate()
    }

    private fun addPoint(viewX: Float, viewY: Float, eventTime: Long) {
        val page = editor?.page ?: return
        val viewport = viewport ?: return
        val x = viewport.toPageX(viewX).coerceIn(0f, page.width.toFloat())
        val y = viewport.toPageY(viewY).coerceIn(0f, page.height.toFloat())
        val last = points.lastOrNull()
        // Skip samples closer than a fraction of a unit (on screen): they add size, not shape.
        if (last != null && hypot(x - last.x, y - last.y) < MIN_DISTANCE / zoom) return
        val point = InkPoint(x, y, (eventTime - downTime).toInt())
        points += point
        extendLive(last, point)
        invalidate()
    }

    /**
     * Adds [point], which follows [previous] (null for the first), to the live stroke, with the geometry of
     * [InkRenderer.pathOf]. Its curve up to a midpoint never changes when a point is added, so only the new segment
     * is appended to [livePath] and just the final straight stretch, [liveTail], is rebuilt.
     */
    private fun extendLive(previous: InkPoint?, point: InkPoint) {
        val scale = scale
        val x = point.x * scale
        val y = point.y * scale
        if (previous == null) {
            livePath.rewind()
            livePath.moveTo(x, y)
            liveDrawing = true
            // A zero-length line, which the round cap draws as a dot.
            liveTail.rewind()
            liveTail.moveTo(x, y)
            liveTail.lineTo(x, y)
            return
        }
        val midX = (previous.x + point.x) / 2 * scale
        val midY = (previous.y + point.y) / 2 * scale
        if (points.size == 2) livePath.lineTo(midX, midY) else livePath.quadTo(previous.x * scale, previous.y * scale, midX, midY)
        liveTail.rewind()
        liveTail.moveTo(midX, midY)
        liveTail.lineTo(x, y)
    }

    private fun clearLive() {
        liveDrawing = false
        livePath.rewind()
        liveTail.rewind()
    }

    private fun select(editor: InkEditor, event: MotionEvent, index: Int) {
        val viewport = viewport ?: return
        val x = viewport.toPageX(event.getX(index))
        val y = viewport.toPageY(event.getY(index))
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
        // The cache lacks the strokes that were moving: they come back where they are now (or were, if dropped).
        refreshAfter()
    }

    /** Fills the shape under the tap, where the stylus first touched. */
    private fun fillAt(editor: InkEditor, viewX: Float, viewY: Float) {
        val viewport = viewport ?: return
        val started = System.nanoTime()
        val filled = editor.fill(viewport.toPageX(viewX), viewport.toPageY(viewY), penColor, zoom, startedAt)
        if (BuildConfig.DEBUG) Log.d(TAG, "fill in ${(System.nanoTime() - started) / 1_000_000} ms: $filled")
        if (!filled) return onNothingToFill()
        refreshAfter()
        onChange()
    }

    /** Erases at every sample of the event, then redraws what changed once. */
    private fun eraseAlong(editor: InkEditor, event: MotionEvent, index: Int) {
        var erased = false
        for (h in 0 until event.historySize) erased = erase(editor, event.getHistoricalX(index, h), event.getHistoricalY(index, h)) || erased
        erased = erase(editor, event.getX(index), event.getY(index)) || erased
        eraserX = event.getX(index)
        eraserY = event.getY(index)
        if (erased) refreshAfter() else invalidate()
    }

    /** Erases from the previous sample to this one, in steps of half the eraser so a quick rub leaves no gaps. */
    private fun erase(editor: InkEditor, viewX: Float, viewY: Float): Boolean {
        val viewport = viewport ?: return false
        val x = viewport.toPageX(viewX)
        val y = viewport.toPageY(viewY)
        // The same size on screen at any zoom: precise when zoomed in.
        val radius = eraserRadius / zoom
        val fromX = if (lastErasedX.isNaN()) x else lastErasedX
        val fromY = if (lastErasedX.isNaN()) y else lastErasedY
        val steps = max(1, ceil(hypot(x - fromX, y - fromY) / (radius / 2)).toInt())
        var erased = false
        for (i in 1..steps) {
            val px = fromX + (x - fromX) * i / steps
            val py = fromY + (y - fromY) * i / steps
            erased = (if (eraseWholeStrokes) editor.eraseStrokesAt(px, py, radius) else editor.eraseAreaAt(px, py, radius)) || erased
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

        /** A second finger within this time, or before the first has moved [FRESH_TRAVEL_DP], starts a pinch. */
        const val FRESH_MILLIS = 150L
        const val FRESH_TRAVEL_DP = 8f

        /** Pixels added around an area redrawn on its own, for antialiased edges. */
        const val AREA_SLACK = 2f
    }
}
