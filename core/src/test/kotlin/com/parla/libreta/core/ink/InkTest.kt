package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BLACK = 0xff202124.toInt()

internal fun stroke(vararg xy: Float, width: Float = 3f, color: Int = BLACK): Stroke =
    Stroke(color, width, startedAt = 1_760_197_500_000, points = (xy.indices step 2).map { InkPoint(xy[it], xy[it + 1], it * 8) })

/** Coordinates rounded to 0.01, to compare points computed in floating point. */
private fun List<InkPoint>.xs() = map { Math.round(it.x * 100) / 100f }
private fun List<InkPoint>.ys() = map { Math.round(it.y * 100) / 100f }

class StrokeTest {

    @Test
    fun `hit test follows the segments and the stroke width`() {
        val line = stroke(0f, 0f, 100f, 0f, width = 4f)

        assertTrue(line.isHitBy(50f, 5f, radius = 4f))   // 5 <= 4 + 2
        assertFalse(line.isHitBy(50f, 7f, radius = 4f))
        assertFalse(line.isHitBy(110f, 0f, radius = 4f)) // past the end
        assertTrue(stroke(10f, 10f).isHitBy(12f, 12f, radius = 2f)) // a dot
    }

    @Test
    fun `erasing the middle cuts the stroke in two at the edge of the eraser`() {
        val line = stroke(0f, 0f, 100f, 0f, width = 2f) // t = 0 and 16

        val pieces = line.erasedAt(50f, 0f, radius = 9f) // reach 10

        assertEquals(2, pieces.size)
        assertEquals(listOf(0f, 40f), pieces[0].points.xs())
        assertEquals(listOf(60f, 100f), pieces[1].points.xs())
        // The right piece starts its own clock where the eraser let go of the pen.
        assertEquals(line.startedAt + 10, pieces[1].startedAt)
        assertEquals(listOf(0, 6), pieces[1].points.map { it.t })
    }

    @Test
    fun `a segment that crosses the eraser between two samples is cut too`() {
        val pieces = stroke(0f, 0f, 100f, 0f, 100f, 100f, width = 0f).erasedAt(100f, 50f, radius = 10f)

        assertEquals(listOf(listOf(0f, 100f, 100f), listOf(100f, 100f)), pieces.map { it.points.xs() })
        assertEquals(listOf(0f, 0f, 40f), pieces[0].points.ys())
        assertEquals(listOf(60f, 100f), pieces[1].points.ys())
    }

    @Test
    fun `erasing an end shortens the stroke, and a miss leaves it as it is`() {
        val line = stroke(0f, 0f, 100f, 0f, width = 0f)

        assertEquals(listOf(listOf(10f, 100f)), line.erasedAt(0f, 0f, radius = 10f).map { it.points.xs() })
        assertEquals(listOf(line), line.erasedAt(50f, 30f, radius = 10f))
        assertTrue(line.erasedAt(50f, 0f, radius = 60f).isEmpty())
        assertTrue(stroke(5f, 5f).erasedAt(6f, 6f, radius = 1f).isEmpty())
    }

    @Test
    fun `crumbs left at the edge of the eraser are dropped`() {
        val line = stroke(0f, 0f, 20.8f, 0f, width = 0f)

        assertTrue(line.erasedAt(10f, 0f, radius = 10f).single().points.first().x > 19.9f)
        assertTrue(stroke(0f, 0f, 10.2f, 0f, width = 0f).erasedAt(0f, 0f, radius = 10f).isEmpty())
    }
}

class InkEditorTest {

    private val editor = InkEditor(InkDocument.blank(600, 900))

    @Test
    fun `strokes can be undone and redone`() {
        editor.addStroke(stroke(0f, 0f, 10f, 10f))
        editor.addStroke(stroke(20f, 20f, 30f, 30f))

        editor.undo()
        assertEquals(1, editor.page.strokes.size)
        editor.redo()
        assertEquals(2, editor.page.strokes.size)
        assertTrue(editor.isModified)
    }

    @Test
    fun `undoing everything returns to the saved document`() {
        editor.addStroke(stroke(0f, 0f, 10f, 10f))
        editor.undo()

        assertFalse(editor.isModified)
        assertFalse(editor.canUndo)
    }

    @Test
    fun `a new change clears redo`() {
        editor.addStroke(stroke(0f, 0f))
        editor.undo()
        editor.addStroke(stroke(5f, 5f))

        assertFalse(editor.canRedo)
    }

    @Test
    fun `stroke eraser removes only the strokes it touches`() {
        editor.addStroke(stroke(0f, 0f, 100f, 0f))
        editor.addStroke(stroke(0f, 50f, 100f, 50f))
        editor.addStroke(stroke(0f, 100f, 100f, 100f))

        assertTrue(editor.eraseStrokesAt(50f, 50f, radius = 5f))
        assertFalse(editor.eraseStrokesAt(50f, 75f, radius = 5f))

        assertEquals(listOf(0f, 100f), editor.page.strokes.map { it.points[0].y })
        editor.undo()
        assertEquals(3, editor.page.strokes.size)
    }

    @Test
    fun `area eraser cuts strokes and keeps the rest in place`() {
        editor.addStroke(stroke(0f, 0f, 100f, 0f))
        editor.addStroke(stroke(0f, 50f, 100f, 50f))

        assertTrue(editor.eraseAreaAt(50f, 0f, radius = 5f))
        assertFalse(editor.eraseAreaAt(50f, 25f, radius = 5f))

        // The two halves of the first stroke stay where it was, before the second one.
        assertEquals(listOf(0f, 0f, 50f), editor.page.strokes.map { it.points[0].y })
        assertEquals(listOf(0f, 56.5f, 0f), editor.page.strokes.map { it.points.xs()[0] }) // reach 5 + 3 / 2
    }

    @Test
    fun `a gesture is undone in one step`() {
        editor.addStroke(stroke(0f, 0f, 100f, 0f))
        editor.addStroke(stroke(0f, 50f, 100f, 50f))

        editor.beginGesture()
        for (x in 0..100 step 5) editor.eraseAreaAt(x.toFloat(), 0f, radius = 5f)
        editor.eraseAreaAt(50f, 50f, radius = 5f)
        editor.endGesture()

        assertEquals(2, editor.page.strokes.size)
        editor.undo()
        assertEquals(2, editor.page.strokes.size)
        assertEquals(listOf(0f, 50f), editor.page.strokes.map { it.points[0].y })
        assertEquals(2, editor.page.strokes[0].points.size)
        editor.redo()
        assertEquals(listOf(50f, 50f), editor.page.strokes.map { it.points[0].y })
    }

    @Test
    fun `a gesture that changes nothing leaves no undo step and keeps redo`() {
        editor.addStroke(stroke(0f, 0f, 100f, 0f))
        editor.undo()

        editor.beginGesture()
        editor.eraseAreaAt(50f, 50f, radius = 5f)
        editor.endGesture()

        assertFalse(editor.canUndo)
        assertTrue(editor.canRedo)
    }

    @Test
    fun `pages are added after the current one and keep size and paper`() {
        editor.setPaper(Paper.GRID)
        editor.addPage()

        assertEquals(2, editor.pageCount)
        assertEquals(1, editor.pageIndex)
        assertEquals(InkPage(600, 900, Paper.GRID), editor.page)

        editor.undo()
        assertEquals(1, editor.pageCount)
        assertEquals(0, editor.pageIndex)
    }

    @Test
    fun `undo is bounded`() {
        val small = InkEditor(InkDocument.blank(10, 10), maxUndo = 2)
        repeat(5) { small.addStroke(stroke(it.toFloat(), 0f)) }
        repeat(5) { small.undo() }

        assertEquals(3, small.page.strokes.size)
    }
    @Test
    fun `moving, scaling, restyling, deleting and pasting strokes are one undo step each`() {
        val editor = InkEditor(InkDocument.blank(600, 900))
        editor.addStroke(stroke(10f, 10f, 20f, 20f))
        editor.addStroke(stroke(100f, 100f, 110f, 100f))
        editor.addStroke(stroke(300f, 300f))
        val original = editor.document

        editor.transformStrokes(listOf(0, 2), dx = 5f, dy = 10f, scale = 2f, pivotX = 10f, pivotY = 10f)
        assertEquals(listOf(InkPoint(15f, 20f, 0), InkPoint(35f, 40f, 16)), editor.page.strokes[0].points)
        assertEquals(6f, editor.page.strokes[0].width, 0f)
        assertEquals(1_760_197_500_000, editor.page.strokes[0].startedAt)
        assertEquals(original.pages[0].strokes[1], editor.page.strokes[1])
        editor.undo()
        assertEquals(original, editor.document)

        editor.restyleStrokes(listOf(1), color = 0xffff0000.toInt(), width = 6f)
        assertEquals(0xffff0000.toInt(), editor.page.strokes[1].color)
        assertEquals(6f, editor.page.strokes[1].width, 0f)
        editor.undo()

        editor.deleteStrokes(listOf(0, 2))
        assertEquals(listOf(original.pages[0].strokes[1]), editor.page.strokes)
        editor.undo()
        assertEquals(original, editor.document)

        val pasted = editor.insertStrokes(listOf(stroke(1f, 1f), stroke(2f, 2f)))
        assertEquals(listOf(3, 4), pasted)
        editor.undo()
        assertEquals(original, editor.document)
    }

    @Test
    fun `a change that changes nothing adds no undo step`() {
        val editor = InkEditor(InkDocument.blank(600, 900))
        editor.addStroke(stroke(10f, 10f, 20f, 20f))
        editor.transformStrokes(listOf(0), 0f, 0f)
        editor.restyleStrokes(listOf(0), width = 3f)
        editor.undo()
        assertEquals(0, editor.page.strokes.size)
    }
    @Test
    fun `cancelling a gesture undoes it without an undo step`() {
        val editor = InkEditor(InkDocument.blank(600, 900))
        editor.addStroke(stroke(10f, 10f, 100f, 10f))
        val before = editor.document

        editor.beginGesture()
        editor.eraseStrokesAt(50f, 10f, 5f)
        editor.cancelGesture()

        assertEquals(before, editor.document)
        editor.undo()
        assertEquals(0, editor.page.strokes.size)
        assertFalse(editor.canUndo)
    }
    @Test
    fun `a page of another size can be added, and saved with it`() {
        val editor = InkEditor(InkDocument.blank(600, 851, Paper.GRID))

        editor.addPage(width = 851, height = 600)

        assertEquals(1, editor.pageIndex)
        assertEquals(InkPage(851, 600, Paper.GRID), editor.page)
        assertEquals(editor.document, InkJson.decode(InkJson.encode(editor.document)))
    }
    @Test
    fun `a mark follows its strokes, goes when they are erased, and comes back with undo`() {
        val editor = InkEditor(InkDocument.blank(600, 900))
        editor.addStroke(stroke(10f, 10f, 50f, 10f).copy(startedAt = 1))
        editor.addStroke(stroke(10f, 30f, 50f, 30f).copy(startedAt = 2))
        editor.addStroke(stroke(10f, 300f, 50f, 300f).copy(startedAt = 3))

        editor.addMark(InkMark.Kind.TITLE, "  Viaje  ", listOf(0, 1))
        assertEquals(listOf(InkMark(InkMark.Kind.TITLE, "Viaje", setOf(1L, 2L))), editor.page.marks)

        editor.transformStrokes(listOf(0, 1), dx = 100f, dy = 0f)
        assertEquals(108.5f, editor.page.boundsOf(editor.page.marks[0])!!.left, 0.001f)

        editor.deleteStrokes(listOf(0))
        assertEquals(setOf(2L), editor.page.marks[0].strokes)
        editor.deleteStrokes(listOf(0))
        assertEquals(emptyList<InkMark>(), editor.page.marks)

        editor.undo()
        assertEquals(setOf(2L), editor.page.marks.single().strokes)
    }

    @Test
    fun `marking strokes again replaces their mark of the same kind`() {
        val editor = InkEditor(InkDocument.blank(600, 900))
        editor.addStroke(stroke(10f, 10f, 50f, 10f).copy(startedAt = 1))
        editor.addMark(InkMark.Kind.TAG, "viaje", listOf(0))
        editor.addMark(InkMark.Kind.TAG, "lisboa", listOf(0))
        editor.addMark(InkMark.Kind.TITLE, "Lisboa", listOf(0))

        assertEquals(listOf("lisboa", "Lisboa"), editor.page.marks.map { it.text })
        assertEquals("viaje-a-lisboa", InkMark.tagText(" #Viaje a  Lisboa "))
    }

    @Test
    fun `the outline lists titles by page and top to bottom, and tags once`() {
        val page1 = InkPage(600, 900, Paper.LINED, listOf(stroke(10f, 400f).copy(startedAt = 1), stroke(10f, 50f).copy(startedAt = 2)),
            listOf(InkMark(InkMark.Kind.TITLE, "Abajo", setOf(1L)), InkMark(InkMark.Kind.TITLE, "Arriba", setOf(2L)), InkMark(InkMark.Kind.TAG, "viaje", setOf(1L))))
        val page2 = InkPage(600, 900, Paper.LINED, listOf(stroke(10f, 10f).copy(startedAt = 3)),
            listOf(InkMark(InkMark.Kind.TITLE, "Otra", setOf(3L)), InkMark(InkMark.Kind.TAG, "viaje", setOf(3L))))

        val outline = InkOutline.of(InkDocument(listOf(page1, page2)))

        assertEquals(listOf("Arriba" to 1, "Abajo" to 1, "Otra" to 2), outline.titles)
        assertEquals(listOf("viaje"), outline.tags)
    }
}

class InkJsonTest {

    @Test
    fun `round-trips a document`() {
        val document = InkDocument(
            listOf(
                InkPage(600, 900, Paper.LINED, listOf(stroke(12.5f, 40f, 13f, 41.56f), stroke(1f, 2f, color = 0x801a56db.toInt()))),
                InkPage(600, 900, Paper.BLANK),
            ),
        )

        val decoded = InkJson.decode(InkJson.encode(document))

        assertEquals(2, decoded.pages.size)
        assertEquals(Paper.LINED, decoded.pages[0].paper)
        assertEquals(0x801a56db.toInt(), decoded.pages[0].strokes[1].color)
        assertEquals(41.6f, decoded.pages[0].strokes[0].points[1].y, 0.001f) // rounded to 0.1
        assertEquals(1_760_197_500_000, decoded.pages[0].strokes[0].startedAt)
        assertEquals(InkPage(600, 900, Paper.BLANK), decoded.pages[1])
    }

    @Test
    fun `numbers are written compactly`() {
        val json = InkJson.encode(InkDocument(listOf(InkPage(10, 10, Paper.BLANK, listOf(stroke(12f, 40.25f))))))

        assertTrue(json, json.contains("\"points\":[12,40.3,0]"))
    }

    @Test
    fun `reads whitespace, escapes and unknown fields`() {
        val json = """
            { "format" : "libreta-ink", "version" : 1, "extra": {"a": [true, null, "x\"é"]},
              "pages" : [ { "width": 600, "height": 900, "paper": "grid",
                "strokes": [ { "color": "#202124", "width": 3, "start": 1, "points": [ 1, 2, 0, 3.5, -4e0, 8 ] } ] } ] }
        """.trimIndent()

        val page = InkJson.decode(json).pages.single()

        assertEquals(Paper.GRID, page.paper)
        assertEquals(BLACK, page.strokes.single().color)
        assertEquals(InkPoint(3.5f, -4f, 8), page.strokes.single().points[1])
    }

    @Test
    fun `rejects other formats and newer versions`() {
        listOf(
            """{"format":"other","version":1,"pages":[]}""",
            """{"format":"libreta-ink","version":99,"pages":[]}""",
            """{"format":"libreta-ink","version":1,"pages":[{"width":1,"height":1,"strokes":[{"color":"#000000","width":1,"start":0,"points":[1,2]}]}]}""",
            """{"format":"libreta-ink",""",
        ).forEach { json ->
            try {
                InkJson.decode(json)
                throw AssertionError("accepted: $json")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
    @Test
    fun `marks round-trip in version 2, and version 1 files still read`() {
        val page = InkPage(600, 900, Paper.LINED, listOf(stroke(1f, 2f).copy(startedAt = 1760197500000)),
            listOf(InkMark(InkMark.Kind.TITLE, "Viaje \"a\" Lisboa", setOf(1760197500000))))
        val document = InkDocument(listOf(page))

        assertEquals(document, InkJson.decode(InkJson.encode(document)))
        val v1 = """{"format":"libreta-ink","version":1,"pages":[{"width":600,"height":900,"paper":"lined","strokes":[]}]}"""
        assertEquals(emptyList<InkMark>(), InkJson.decode(v1).pages[0].marks)
    }

    @Test
    fun `a document without marks is written as version 1, so fingerprints from before marks still match`() {
        val page = InkPage(600, 900, Paper.LINED, listOf(stroke(1f, 2f)))

        assertTrue(InkJson.encode(InkDocument(listOf(page))).startsWith("""{"format":"libreta-ink","version":1,"""))
        assertEquals(
            com.parla.libreta.core.note.ContentHash.of("""{"format":"libreta-ink","version":1,"pages":[""" +
                """{"width":600,"height":900,"paper":"lined","strokes":[{"color":"#ff202124","width":3,"start":1760197500000,"points":[1,2,0]}]}]}""").value,
            InkTranscripts.hashOf(page),
        )
    }

    @Test
    fun `marking a page does not change its transcription fingerprint`() {
        val page = InkPage(600, 900, Paper.LINED, listOf(stroke(1f, 2f).copy(startedAt = 5)))
        val marked = page.copy(marks = listOf(InkMark(InkMark.Kind.TITLE, "T", setOf(5L))))

        assertEquals(InkTranscripts.hashOf(page), InkTranscripts.hashOf(marked))
    }
}

class InkNoteFormatTest {

    @Test
    fun `new note has a title, one image per page and the hidden strokes reference`() {
        assertEquals(
            """
            # Nota a mano

            ![Página 1](.attachments.k3/pagina-1.png)

            ![Página 2](.attachments.k3/pagina-2.png)

            [libreta-ink]: .attachments.k3/libreta.ink.json

            """.trimIndent(),
            InkNoteFormat.compose("Nota a mano", ".attachments.k3", pageCount = 2),
        )
    }

    @Test
    fun `update keeps the user's text and replaces only the managed lines`() {
        val edited = """
            # Lista

            Texto reconocido: comprar pan
            ![Página 1](.attachments.k3/pagina-1.png)
            ![Mi foto](.attachments.k3/foto.jpg)

            [libreta-ink]: .attachments.k3/libreta.ink.json
        """.trimIndent()

        val updated = InkNoteFormat.update(edited, ".attachments.k3", pageCount = 2)

        assertTrue(updated.startsWith("# Lista\n\nTexto reconocido: comprar pan\n![Mi foto](.attachments.k3/foto.jpg)\n"))
        assertTrue(updated.contains("pagina-2.png"))
        assertEquals(1, Regex("libreta-ink").findAll(updated).count())
        assertEquals(updated, InkNoteFormat.update(updated, ".attachments.k3", pageCount = 2))
    }

    @Test
    fun `strokes may be where the reference says or in any linked attachments folder`() {
        val note = "# x\n\n![Foto](.attachments.p1/foto.jpg)\n" + InkNoteFormat.compose("x", ".attachments.k3", 1).substringAfter("\n")

        assertEquals(
            listOf(".attachments.k3/libreta.ink.json", ".attachments.p1/libreta.ink.json"),
            InkNoteFormat.inkFileCandidates(note),
        )
        // Nextcloud Notes' editor dropped the reference line: the page images still point at the folder.
        assertEquals(
            listOf(".attachments.k3/libreta.ink.json"),
            InkNoteFormat.inkFileCandidates("# x\n\n![Página 1](.attachments.k3/pagina-1.png)\n"),
        )
        assertEquals(emptyList<String>(), InkNoteFormat.inkFileCandidates("# Normal\n\n[libreta-ink]: ../fuera.json\n"))
        assertEquals(".attachments.k3", InkNoteFormat.folderOf(".attachments.k3/libreta.ink.json"))
    }

    @Test
    fun `the index and tags go above the pages, are regenerated, and leave the user's text alone`() {
        val outline = InkOutline(listOf("Viaje" to 1, "Gastos" to 2), listOf("viajes", "2026"))
        val composed = InkNoteFormat.compose("Lisboa", ".attachments.k3", 2, outline)

        assertEquals(
            "# Lisboa\n\n## Índice\n\n- Viaje (pág. 1)\n- Gastos (pág. 2)\n\nEtiquetas: #viajes #2026\n\n" +
                "![Página 1](.attachments.k3/pagina-1.png)\n\n![Página 2](.attachments.k3/pagina-2.png)\n\n" +
                "[libreta-ink]: .attachments.k3/libreta.ink.json\n",
            composed,
        )
        val edited = composed.replace("# Lisboa\n", "# Lisboa\n\nNotas del viaje\n")
        val fewer = InkNoteFormat.update(edited, ".attachments.k3", 2, InkOutline(listOf("Viaje" to 1), emptyList()))
        assertEquals(
            "# Lisboa\n\nNotas del viaje\n\n## Índice\n\n- Viaje (pág. 1)\n\n![Página 1](.attachments.k3/pagina-1.png)\n\n" +
                "![Página 2](.attachments.k3/pagina-2.png)\n\n[libreta-ink]: .attachments.k3/libreta.ink.json\n",
            fewer,
        )
        assertEquals(fewer, InkNoteFormat.update(fewer, ".attachments.k3", 2, InkOutline(listOf("Viaje" to 1), emptyList())))
        assertEquals(
            "# Lisboa\n\nNotas del viaje\n\n![Página 1](.attachments.k3/pagina-1.png)\n\n![Página 2](.attachments.k3/pagina-2.png)\n\n" +
                "[libreta-ink]: .attachments.k3/libreta.ink.json\n",
            InkNoteFormat.update(fewer, ".attachments.k3", 2),
        )
    }
}

