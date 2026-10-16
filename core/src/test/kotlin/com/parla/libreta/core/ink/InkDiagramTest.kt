package com.parla.libreta.core.ink

import com.parla.libreta.core.ink.InkDiagram.Arrow
import com.parla.libreta.core.ink.InkDiagram.Edge
import com.parla.libreta.core.ink.InkDiagram.Node
import com.parla.libreta.core.ink.InkDiagram.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class InkDiagramTest {

    /** An image of 1000×1000 pixels over the page area 0..1000, so thousandths are page units. */
    private val frame = InkFrame(InkRect(0f, 0f, 1000f, 1000f), 1f)

    @Test
    fun `reads nodes and edges from the AI's JSON, skipping what does not hold together`() {
        val json = """
            {"nodos":[
              {"id":"a","texto":"Inicio","forma":"elipse","caja":[100,100,300,200]},
              {"id":"b","texto":"¿Vale?","forma":"rombo","caja":[100,400,300,500]},
              {"id":"c","texto":"sin caja"},
              {"id":"a","texto":"repetido","caja":[0,0,1,1]}],
             "conexiones":[
              {"desde":"a","hasta":"b","flecha":"fin","texto":"sí"},
              {"desde":"a","hasta":"c"},
              {"desde":"b","hasta":"b"}]}
        """.trimIndent()

        val diagram = InkDiagram.parse(json, frame)!!

        assertEquals(listOf("a", "b"), diagram.nodes.map { it.id })
        assertEquals(Shape.ELLIPSE, diagram.nodes[0].shape)
        assertEquals(InkRect(100f, 400f, 300f, 500f), diagram.nodes[1].box)
        assertEquals(listOf(Edge("a", "b", Arrow.END, "sí")), diagram.edges)
    }

    @Test
    fun `no diagram in what the AI said`() {
        assertNull(InkDiagram.parse("No veo ningún diagrama.", frame))
        assertNull(InkDiagram.parse("""{"nodos":[]}""", frame))
    }

    @Test
    fun `nodes nearly in line are put in line`() {
        val diagram = InkDiagram(
            listOf(
                Node("a", "", Shape.BOX, InkRect(100f, 100f, 200f, 150f)), // centre 150, 125
                Node("b", "", Shape.BOX, InkRect(106f, 300f, 206f, 350f)), // centre 156, 325
                Node("c", "", Shape.BOX, InkRect(400f, 104f, 500f, 154f)), // centre 450, 129
            ),
            emptyList(),
        ).aligned()

        assertEquals(153f, diagram.nodes[0].centerX, 0.01f)
        assertEquals(153f, diagram.nodes[1].centerX, 0.01f)
        assertEquals(127f, diagram.nodes[0].centerY, 0.01f)
        assertEquals(127f, diagram.nodes[2].centerY, 0.01f)
        assertEquals(450f, diagram.nodes[2].centerX, 0.01f)
    }

    @Test
    fun `a line runs between the outlines, with a gap, and ends in an arrowhead`() {
        val diagram = InkDiagram(
            listOf(Node("a", "", Shape.BOX, InkRect(0f, 0f, 100f, 50f)), Node("b", "", Shape.ELLIPSE, InkRect(0f, 200f, 100f, 300f))),
            listOf(Edge("a", "b", Arrow.END, "")),
        )

        val strokes = diagram.strokes(color = 1, width = 2f, startedAt = 10)

        // Box outline, ellipse outline, line, arrowhead.
        assertEquals(4, strokes.size)
        assertEquals(10, strokes[0].points.size) // five corners, each twice so that they stay sharp
        val line = strokes[2].points
        assertEquals(50f, line[0].x, 0.01f)
        assertEquals(50f + InkDiagram.GAP + 2f, line[0].y, 0.01f) // the box's bottom edge, plus the gap
        assertEquals(200f - InkDiagram.GAP - 2f, line[1].y, 0.01f) // the top of the ellipse, minus the gap
        val head = strokes[3].points.distinct()
        assertEquals(line[1], head[1])
        assertTrue(head[0].y < line[1].y && head[2].y < line[1].y) // the wings point back up the line
        assertEquals(InkDiagram.ARROW_MIN, hypot(head[0].x - head[1].x, head[0].y - head[1].y), 0.01f)
        assertEquals((10L until 14L).toList(), strokes.map { it.startedAt })
    }

    @Test
    fun `a diamond is met at its outline, and text alone has none`() {
        val diagram = InkDiagram(
            listOf(Node("a", "", Shape.DIAMOND, InkRect(0f, 0f, 100f, 100f)), Node("b", "x", Shape.NONE, InkRect(300f, 0f, 400f, 100f))),
            listOf(Edge("a", "b", Arrow.NONE, "")),
        )

        val strokes = diagram.strokes(color = 1, width = 0f, startedAt = 0)

        assertEquals(2, strokes.size) // the diamond and the line
        assertEquals(100f + InkDiagram.GAP, strokes[1].points[0].x, 0.01f)
        assertEquals(300f - InkDiagram.GAP, strokes[1].points[1].x, 0.01f)
    }
}
