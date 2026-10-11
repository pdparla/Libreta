package com.parla.libreta.ui.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.parla.libreta.BuildConfig
import com.parla.libreta.core.ink.InkEditor
import com.parla.libreta.core.ink.InkPoint
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
 */
class InkView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Tool { PEN, ERASER }

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

    /** Redraws everything: after undo, redo, erasing, a page change or a new document. */
    fun refresh() {
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
        InkRenderer.draw(canvas, page, scale)
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
        val path = livePath ?: return
        canvas.save()
        canvas.translate(offsetX, 0f)
        canvas.drawPath(path, livePaint!!)
        canvas.restore()
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
        }
    }

    private fun move(editor: InkEditor, event: MotionEvent, index: Int) {
        if (tool == Tool.ERASER) return eraseAlong(editor, event, index)
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
