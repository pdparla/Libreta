package com.parla.libreta.ui.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.parla.libreta.core.ink.InkPage
import com.parla.libreta.core.ink.InkPoint
import com.parla.libreta.core.ink.PageRenderer
import com.parla.libreta.core.ink.Paper
import com.parla.libreta.core.ink.Stroke
import java.io.ByteArrayOutputStream

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

    /** The page as a PNG at 1 pixel per unit, for Nextcloud Notes. */
    override fun render(page: InkPage): ByteArray {
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap), page, scale = 1f)
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
