package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typed text: a stroke with a [Stroke.text], its box in its two points, its font size as its width. */
private fun typed(text: String, left: Float, top: Float, right: Float, bottom: Float, size: Float = 24f, startedAt: Long = 9_000) =
    Stroke(0xff202124.toInt(), size, startedAt, listOf(InkPoint(left, top, 0), InkPoint(right, bottom, 0)), text = text)

class InkTextTest {

    private val hello = typed("Hola, ¿qué tal?", 100f, 200f, 300f, 230f)

    @Test
    fun `its box is its two corners, without the font size around them`() {
        assertEquals(InkRect(100f, 200f, 300f, 230f), hello.bounds)
    }

    @Test
    fun `a selection takes it when it touches the box, even away from its diagonal`() {
        assertTrue(hello.touches(InkRect(280f, 200f, 320f, 205f))) // upper-right corner
        assertFalse(hello.touches(InkRect(310f, 200f, 330f, 230f)))
    }

    @Test
    fun `the eraser hits the box and takes the whole text`() {
        assertTrue(hello.isHitBy(290f, 202f, radius = 1f))
        assertTrue(hello.isHitBy(305f, 215f, radius = 6f))
        assertFalse(hello.isHitBy(305f, 215f, radius = 4f))

        assertTrue(hello.erasedAt(200f, 215f, radius = 2f).isEmpty())
        assertEquals(listOf(hello), hello.erasedAt(200f, 260f, radius = 2f))
    }

    @Test
    fun `scaling grows the box and the font size together`() {
        val bigger = hello.transformed(dx = 0f, dy = 0f, scale = 2f, pivotX = 100f, pivotY = 200f)

        assertEquals(InkRect(100f, 200f, 500f, 260f), bigger.bounds)
        assertEquals(48f, bigger.width, 0.001f)
        assertEquals(hello.text, bigger.text)
    }

    @Test
    fun `round-trips in JSON as version 3, escapes included`() {
        val quoted = typed("Dice \"hola\"\ny se va", 10f, 20f, 110f, 80f)
        val document = InkDocument(listOf(InkPage(600, 900, Paper.LINED, listOf(stroke(1f, 2f), quoted))))

        val json = InkJson.encode(document)

        assertTrue(json, json.startsWith("""{"format":"libreta-ink","version":3,"""))
        assertEquals(document, InkJson.decode(json))
    }

    @Test
    fun `typed text without the two corners of its box is refused`() {
        val json = """{"format":"libreta-ink","version":3,"pages":[{"width":10,"height":10,"paper":"blank","strokes":[""" +
            """{"color":"#ff000000","width":3,"start":1,"text":"a","points":[1,2,0]}]}]}"""
        try {
            InkJson.decode(json)
            throw AssertionError("accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `a fill does not run under typed text`() {
        val page = InkPage(200, 200, Paper.BLANK, listOf(typed("Texto", 50f, 50f, 150f, 150f)))

        assertNull(InkFill.at(page, 100f, 100f, 0xffffeb3b.toInt(), zoom = 1f, startedAt = 1))
        val around = InkFill.at(page, 10f, 10f, 0xffffeb3b.toInt(), zoom = 1f, startedAt = 1)!!
        // No band crosses the middle of the box.
        assertFalse(around.bands.any { band -> band.points.first().y in 60f..140f && band.points.minOf { it.x } < 100f && band.points.maxOf { it.x } > 100f })
    }

    @Test
    fun `replacing handwriting with typed text is one step and keeps its marks`() {
        val first = stroke(10f, 10f, 20f, 20f).copy(startedAt = 1)
        val second = stroke(30f, 10f, 40f, 20f).copy(startedAt = 2)
        val drawing = stroke(100f, 100f, 150f, 150f).copy(startedAt = 3)
        val editor = InkEditor(InkDocument(listOf(InkPage(600, 900, Paper.LINED, listOf(first, second, drawing)))))
        editor.addMark(InkMark.Kind.TITLE, "Viaje", listOf(0, 1))

        val text = typed("Viaje", 10f, 10f, 60f, 22f, startedAt = 50)
        val indexes = editor.replaceStrokes(listOf(0, 1), listOf(text))

        assertEquals(listOf(1), indexes)
        assertEquals(listOf(drawing, text), editor.page.strokes)
        assertEquals(listOf(InkMark(InkMark.Kind.TITLE, "Viaje", setOf(50L))), editor.page.marks)
        editor.undo()
        assertEquals(listOf(first, second, drawing), editor.page.strokes)
        assertEquals(setOf(1L, 2L), editor.page.marks.single().strokes)
    }
}

class InkFiguresTest {

    @Test
    fun `finds placeholders, with page, corners and caption`() {
        val markdown = "## Circuito\n\n![Esquema del circuito](dibujo:2,120,340,560.5,800)\n\nTexto\n\n![](<dibujo: 1; 0, 0, 10, 10>)"

        val found = InkFigures.placeholders(markdown)

        assertEquals(2, found.size)
        assertEquals("Esquema del circuito", found[0].caption)
        assertEquals(1, found[0].page)
        assertEquals(listOf(120f, 340f, 560.5f, 800f), listOf(found[0].x1, found[0].y1, found[0].x2, found[0].y2))
        assertEquals(0, found[1].page)
        assertEquals("", found[1].caption)
        // Ordinary images are left alone.
        assertTrue(InkFigures.placeholders("![Foto](.attachments.a/foto.png)").isEmpty())
    }

    @Test
    fun `replaces placeholders and tidies the lines a removed one leaves`() {
        val markdown = "Antes\n\n![A](dibujo:1,0,0,1,1)\n\nEntre\n\n![B](dibujo:1,0,0,1,1)\n\nDespués"

        val result = InkFigures.replace(markdown) { i, placeholder -> if (i == 0) "[${placeholder.caption}]" else null }

        assertEquals("Antes\n\n[A]\n\nEntre\n\nDespués", result)
    }

    @Test
    fun `takes the strokes mostly inside the frame, not the writing that grazes it`() {
        val drawing = stroke(100f, 100f, 200f, 200f).copy(startedAt = 1)
        val label = stroke(120f, 150f, 150f, 160f).copy(startedAt = 2)
        val grazing = stroke(180f, 190f, 400f, 210f).copy(startedAt = 3) // a line of text below, barely inside
        val far = stroke(400f, 400f, 450f, 450f).copy(startedAt = 4)
        val page = InkPage(600, 900, Paper.BLANK, listOf(drawing, label, grazing, far))

        // A frame a little tight on the drawing still takes it whole.
        assertEquals(listOf(0, 1), InkFigures.strokesIn(page, InkRect(105f, 105f, 195f, 195f)))
    }

    @Test
    fun `a word cut by the frame goes whole or not at all`() {
        // A drawing, and a word of five letters on its right whose first three letters are inside the frame.
        val drawing = stroke(100f, 100f, 200f, 200f).copy(startedAt = 1)
        val letters = (0 until 5).map { i -> stroke(205f + i * 12f, 140f, 213f + i * 12f, 160f).copy(startedAt = 10L + i) }
        val page = InkPage(600, 900, Paper.BLANK, listOf(drawing) + letters)

        assertEquals(listOf(0, 1, 2, 3, 4, 5), InkFigures.strokesIn(page, InkRect(100f, 100f, 236f, 200f)))
        // Now only the first two letters are inside: the word stays out.
        assertEquals(listOf(0), InkFigures.strokesIn(page, InkRect(100f, 100f, 216f, 200f)))
    }
}

class InkFrameTest {

    @Test
    fun `maps the image's pixels back to the page, margin and scale included`() {
        val page = InkPage(600, 800, Paper.LINED)
        val frame = InkFrame.ofPage(page)

        // The margin is 10% of the longer side (80), and the longer side, 960 units, is cut to 1600 pixels.
        assertEquals(InkRect(-80f, -80f, 680f, 880f), frame.area)
        assertEquals(1600f / 960f, frame.scale, 0.0001f)
        assertEquals(1600, frame.pixelHeight)
        val rect = frame.toPage(frame.scale * 180f, frame.scale * 280f, frame.scale * 80f, frame.scale * 180f)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(100f, rect.top, 0.01f)
        assertEquals(100f, rect.right, 0.01f)
        assertEquals(200f, rect.bottom, 0.01f)
    }

    @Test
    fun `corners outside the image stay on its edge`() {
        val frame = InkFrame(InkRect(0f, 0f, 100f, 100f), 2f)

        assertEquals(InkRect(0f, 50f, 100f, 100f), frame.toPage(-30f, 100f, 900f, 900f))
    }

    @Test
    fun `thousandths are proportions of the image`() {
        val frame = InkFrame(InkRect(0f, 0f, 100f, 50f), 2f)

        assertEquals(InkRect(25f, 10f, 50f, 50f), frame.fromThousandths(250f, 200f, 500f, 1000f))
    }
}
