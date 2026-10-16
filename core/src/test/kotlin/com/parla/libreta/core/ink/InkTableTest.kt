package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkTableTest {

    @Test
    fun `parses the first Markdown table, without the separator, padding short rows`() {
        val markdown = "Aquí está:\n\n| Día | Tarea |\n|:---|---:|\n| Lunes | Compra \\| pan |\n| Martes |\n\nFin"

        assertEquals(
            listOf(listOf("Día", "Tarea"), listOf("Lunes", "Compra | pan"), listOf("Martes", "")),
            InkTable.parse(markdown),
        )
    }

    @Test
    fun `line breaks in cells, and no table at all`() {
        assertEquals(listOf(listOf("a\nb", "c")), InkTable.parse("| a<br>b | c |"))
        assertNull(InkTable.parse("No hay ninguna tabla."))
    }

    @Test
    fun `writes Markdown back with the first row as header`() {
        assertEquals(
            "| Día | Tarea |\n| --- | --- |\n| Lunes | Compra \\| pan<br>leche |",
            InkTable.toMarkdown(listOf(listOf("Día", "Tarea"), listOf("Lunes", "Compra | pan\nleche"))),
        )
    }

    @Test
    fun `columns follow their text, and the table covers the drawn area`() {
        // Text 1 unit wide per character, 1 unit tall, at size 1.
        val cells = listOf(listOf("ab", "abcdef"), listOf("a", "abc"))
        val area = InkRect(100f, 200f, 500f, 300f)

        val layout = InkTable.layout(cells, area) { text -> text.length.toFloat() to 1f }

        // Widths at size 1: 2.8 and 6.8 (9.6 in all); heights 1.5 each (3). Width limits: 400 / 9.6 = 41.7; height: 100 / 3 = 33.3.
        assertEquals(100f / 3f, layout.size, 0.01f)
        assertEquals(area, layout.box)
        // The columns keep their proportion: 2.8 to 6.8.
        assertEquals(400f * 2.8f / 9.6f, layout.columns[1] - layout.columns[0], 0.01f)
        assertEquals(listOf(200f, 250f, 300f), layout.rows.map { Math.round(it * 100) / 100f })
    }

    @Test
    fun `a cramped table keeps the smallest size and grows past the area`() {
        val layout = InkTable.layout(listOf(listOf("una celda muy larga")), InkRect(0f, 0f, 20f, 10f)) { it.length.toFloat() to 1f }

        assertEquals(InkTable.MIN_SIZE, layout.size, 0.001f)
        assertEquals((19 + 0.8f) * InkTable.MIN_SIZE, layout.box.width, 0.01f)
    }

    @Test
    fun `the grid is every row edge and every column edge, as straight strokes`() {
        val layout = InkTable.Layout(10f, listOf(0f, 50f, 120f), listOf(0f, 20f, 40f, 60f))

        val grid = InkTable.gridStrokes(layout, color = 7, width = 2f, startedAt = 100)

        assertEquals(4 + 3, grid.size)
        assertEquals(listOf(InkPoint(0f, 20f, 0), InkPoint(120f, 20f, 0)), grid[1].points)
        assertEquals(listOf(InkPoint(50f, 0f, 0), InkPoint(50f, 60f, 0)), grid[5].points)
        assertEquals((100L until 107L).toList(), grid.map { it.startedAt })
    }

    @Test
    fun `text sits padded from the left and centred in its row`() {
        val layout = InkTable.Layout(10f, listOf(0f, 50f), listOf(0f, 30f))

        assertEquals(4f to 10f, InkTable.textOrigin(layout, 0, 0, textHeight = 1f))
    }
}
