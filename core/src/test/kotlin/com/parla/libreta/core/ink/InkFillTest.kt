package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val YELLOW = 0xffffeb3b.toInt()
private const val BLUE = 0xff1a73e8.toInt()

/** A circle drawn as a stroke, leaving out an arc of [gap] units if asked. */
private fun circle(cx: Float, cy: Float, r: Float, width: Float = 3f, gap: Float = 0f, startedAt: Long = 1_000): Stroke {
    val skip = gap / r
    val steps = 120
    val points = (0..steps).map { i -> skip / 2 + (2 * PI - skip) * i / steps }.mapIndexed { i, a ->
        InkPoint(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), i * 8)
    }
    return Stroke(0xff202124.toInt(), width, startedAt, points)
}

/** Whether some band paints ([x], [y]): within half its thickness of its line. */
private fun List<Stroke>.paints(x: Float, y: Float): Boolean = any { band ->
    val a = band.points.first()
    val b = band.points.last()
    val t = if (b.x == a.x) 0f else ((x - a.x) / (b.x - a.x)).coerceIn(0f, 1f)
    hypot(x - (a.x + t * (b.x - a.x)), y - a.y) <= band.width / 2
}

/** How far from ([cx], [cy]) the bands reach, thickness included. */
private fun List<Stroke>.reach(cx: Float, cy: Float): Float =
    maxOf { band -> band.points.maxOf { hypot(it.x - cx, it.y - cy) } + band.width / 2 }

class InkFillTest {

    private val page = InkPage(400, 400, Paper.BLANK, listOf(circle(200f, 200f, 80f)))

    @Test
    fun `filling inside a circle covers it up to the ink and no further`() {
        val result = InkFill.at(page, 200f, 200f, YELLOW, startedAt = 2_000)!!
        val bands = result.bands

        assertTrue(bands.isNotEmpty())
        assertTrue(bands.all { it.fill && it.color == YELLOW && it.startedAt == 2_000L })
        // Everything inside the ink is painted...
        for (y in 125..275 step 5) for (x in 125..275 step 5) {
            if (hypot(x - 200f, y - 200f) < 80f - 1.5f) assertTrue("($x, $y)", bands.paints(x.toFloat(), y.toFloat()))
        }
        // ...and nothing outside its outer edge.
        assertTrue(bands.reach(200f, 200f) <= 80f + 1.5f)
        assertTrue(result.replaced.isEmpty())
    }

    @Test
    fun `a small gap in the outline does not let the fill out`() {
        val open = page.copy(strokes = listOf(circle(200f, 200f, 80f, gap = 4f)))

        val bands = InkFill.at(open, 200f, 200f, YELLOW, startedAt = 2_000)!!.bands

        assertTrue(bands.reach(200f, 200f) <= 80f + 4f)
    }

    @Test
    fun `outside any shape the open area is filled up to the page edges`() {
        val bands = InkFill.at(page, 10f, 10f, YELLOW, startedAt = 2_000)!!.bands

        assertTrue(bands.paints(2f, 2f))
        assertTrue(bands.paints(398f, 398f))
        assertFalse(bands.paints(200f, 200f))
    }

    @Test
    fun `nothing is filled on the ink or off the page`() {
        assertNull(InkFill.at(page, 280f, 200f, YELLOW, startedAt = 2_000))
        assertNull(InkFill.at(page, -1f, 200f, YELLOW, startedAt = 2_000))
        assertNull(InkFill.at(page, 200f, 400f, YELLOW, startedAt = 2_000))
    }

    @Test
    fun `a shape too small to fill at 1x can be filled zoomed in`() {
        val tiny = page.copy(strokes = listOf(circle(50f, 50f, 3f, width = 0.5f)))

        assertNull(InkFill.at(tiny, 50f, 50f, YELLOW, zoom = 1f, startedAt = 2_000))
        assertTrue(InkFill.at(tiny, 50f, 50f, YELLOW, zoom = 4f, startedAt = 2_000)!!.bands.reach(50f, 50f) <= 3.5f)
    }

    @Test
    fun `the fill goes behind the ink, and filling again recolours it in one undo step`() {
        val editor = InkEditor(InkDocument(listOf(page.copy(strokes = page.strokes + stroke(190f, 200f, 210f, 200f)))))

        assertTrue(editor.fill(200f, 180f, YELLOW, zoom = 1f, startedAt = 2_000))
        val first = editor.page.strokes
        val bands = first.count { it.fill }
        assertTrue(first.take(bands).all { it.fill })
        assertFalse(first.last().fill)

        assertTrue(editor.fill(200f, 180f, BLUE, zoom = 1f, startedAt = 3_000))
        val fills = editor.page.strokes.filter { it.fill }
        assertTrue(fills.all { it.color == BLUE && it.startedAt == 3_000L })
        assertEquals(bands.toFloat(), fills.size.toFloat(), bands / 10f)

        editor.undo()
        assertEquals(first, editor.page.strokes)
        editor.undo()
        assertTrue(editor.page.strokes.none { it.fill })
    }

    @Test
    fun `a fill is erased and selected whole`() {
        val editor = InkEditor(InkDocument(listOf(page)))
        editor.fill(200f, 200f, YELLOW, zoom = 1f, startedAt = 2_000)
        val bands = editor.page.strokes.indices.filter { editor.page.strokes[it].fill }

        assertEquals(bands, InkRect(195f, 195f, 205f, 205f).selectedStrokes(editor.page).filter { editor.page.strokes[it].fill })

        assertTrue(editor.eraseStrokesAt(200f, 200f, 2f))
        assertEquals(listOf(page.strokes[0]), editor.page.strokes)
    }

    @Test
    fun `fill bands round-trip, and other strokes are written as before`() {
        val editor = InkEditor(InkDocument(listOf(page)))
        editor.fill(200f, 200f, YELLOW, zoom = 1f, startedAt = 2_000)

        val json = InkJson.encode(editor.document)
        val decoded = InkJson.decode(json).pages[0].strokes

        assertEquals(editor.page.strokes.count { it.fill }, decoded.count { it.fill })
        assertFalse(InkJson.encode(InkDocument(listOf(page))).contains("fill"))
    }
}
