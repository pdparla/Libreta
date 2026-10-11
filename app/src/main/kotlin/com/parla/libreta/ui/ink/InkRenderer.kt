package com.parla.libreta.ui.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.parla.libreta.BuildConfig
import com.parla.libreta.core.ink.InkPage
import com.parla.libreta.core.ink.InkPoint
import com.parla.libreta.core.ink.InkRect
import com.parla.libreta.core.ink.PageRenderer
import com.parla.libreta.core.ink.Paper
import com.parla.libreta.core.ink.Stroke
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

/** Draws pages: the paper, then the strokes smoothed with quadratic curves through the midpoints of the samples. */
object InkRenderer : PageRenderer {

    /** Distance between ruled lines, in page units; generous for writing with a capacitive stylus. */
    const val LINE_SPACING = 40f
    private const val TOP_MARGIN = 64f
    private const val LEFT_MARGIN = 48f

    private val linePaint = Paint().apply { color = 0xffc5d8ee.toInt(); strokeWidth = 1f }
    private val marginPaint = Paint().apply { color = 0xffefb8b8.toInt(); strokeWidth = 1f }

    fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        strokeWidth = width
    }

    /** Draws [page] scaled by [scale] (view pixels per page unit) on [canvas], from its top-left corner. */
    fun draw(canvas: Canvas, page: InkPage, scale: Float) {
        drawPaper(canvas, page, scale)
        page.strokes.forEach { drawStroke(canvas, it, scale) }
    }

    fun drawPaper(canvas: Canvas, page: InkPage, scale: Float) {
        canvas.drawColor(Color.WHITE)
        // The ruling scales with the page: a 1-pixel line vanishes when a 2× page image is shrunk to the screen.
        linePaint.strokeWidth = max(1f, scale)
        marginPaint.strokeWidth = max(1f, scale)
        val width = page.width * scale
        val height = page.height * scale
        when (page.paper) {
            Paper.BLANK -> Unit
            Paper.LINED -> {
                var y = TOP_MARGIN
                while (y < page.height) {
                    canvas.drawLine(0f, y * scale, width, y * scale, linePaint)
                    y += LINE_SPACING
                }
                canvas.drawLine(LEFT_MARGIN * scale, 0f, LEFT_MARGIN * scale, height, marginPaint)
            }
            Paper.GRID -> {
                val step = LINE_SPACING / 2
                var x = step
                while (x < page.width) {
                    canvas.drawLine(x * scale, 0f, x * scale, height, linePaint)
                    x += step
                }
                var y = step
                while (y < page.height) {
                    canvas.drawLine(0f, y * scale, width, y * scale, linePaint)
                    y += step
                }
            }
        }
    }

    fun drawStroke(canvas: Canvas, stroke: Stroke, scale: Float) {
        val paint = strokePaint(stroke.color, stroke.width * scale)
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            val p = stroke.points[0]
            canvas.drawCircle(p.x * scale, p.y * scale, stroke.width * scale / 2, paint)
        } else {
            canvas.drawPath(pathOf(stroke.points, scale), paint)
        }
    }

    /** [stroke] as a path and its paint at [scale], to draw it many times (while it is being moved) without rebuilding it. */
    fun strokePath(stroke: Stroke, scale: Float): Pair<Path, Paint> {
        val paint = strokePaint(stroke.color, stroke.width * scale)
        if (stroke.points.size > 1) return pathOf(stroke.points, scale) to paint
        paint.style = Paint.Style.FILL
        val p = stroke.points[0]
        return Path().apply { addCircle(p.x * scale, p.y * scale, stroke.width * scale / 2, Path.Direction.CW) } to paint
    }

    /** A smooth path through [points]: straight to the first midpoint, then quadratic curves between midpoints. */
    fun pathOf(points: List<InkPoint>, scale: Float): Path {
        val path = Path()
        val first = points[0]
        path.moveTo(first.x * scale, first.y * scale)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val midX = (previous.x + current.x) / 2 * scale
            val midY = (previous.y + current.y) / 2 * scale
            if (i == 1) path.lineTo(midX, midY) else path.quadTo(previous.x * scale, previous.y * scale, midX, midY)
        }
        val last = points.last()
        path.lineTo(last.x * scale, last.y * scale)
        return path
    }

    /**
     * The page as a PNG at [PAGE_IMAGE_SCALE] pixels per unit, for Nextcloud Notes: at 1 pixel per unit, writing done
     * zoomed in (docs/09-zoom.md) would be unreadable. RGB 565 because a page has no transparency, and it halves the
     * memory the bitmap takes while saving.
     */
    override fun render(page: InkPage): ByteArray {
        val bitmap = Bitmap.createBitmap(page.width * PAGE_IMAGE_SCALE, page.height * PAGE_IMAGE_SCALE, Bitmap.Config.RGB_565)
        try {
            draw(Canvas(bitmap), page, scale = PAGE_IMAGE_SCALE.toFloat())
            return png(bitmap).also {
                if (BuildConfig.DEBUG) Log.d("LibretaInk", "page image ${bitmap.width}x${bitmap.height}: ${it.size} bytes")
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The ink inside [selected] of [page], with a margin, as a PNG for the model: strokes only, on white, without the
     * paper's lines, which add nothing and can be mistaken for writing. [SELECTION_SCALE] pixels per unit, or fewer
     * to keep the longer side within [SELECTION_MAX_SIDE].
     */
    fun renderSelection(page: InkPage, selected: InkRect): ByteArray {
        // A white margin around the ink: a selection snapped to its strokes (Fase 8) leaves letters touching the
        // edges, and the model then reads nothing.
        val margin = max(SELECTION_MARGIN, max(selected.width, selected.height) * 0.1f)
        val area = InkRect(selected.left - margin, selected.top - margin, selected.right + margin, selected.bottom + margin)
        val scale = area.renderScale(SELECTION_SCALE, SELECTION_MAX_SIDE)
        val bitmap = Bitmap.createBitmap(
            ceil(area.width * scale).toInt().coerceAtLeast(1),
            ceil(area.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.translate(-area.left * scale, -area.top * scale)
            page.strokes.forEach { drawStroke(canvas, it, scale) }
            return png(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    private const val PAGE_IMAGE_SCALE = 2
    private const val SELECTION_SCALE = 2f
    private const val SELECTION_MARGIN = 16f
    private const val SELECTION_MAX_SIDE = 1600
}
