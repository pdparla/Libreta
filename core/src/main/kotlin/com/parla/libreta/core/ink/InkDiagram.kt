package com.parla.libreta.core.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A diagram drawn by hand made clean (docs/design/ink.md#clean-diagrams): boxes, ellipses and diamonds with text, and
 * the lines and arrows between them. The AI reads its structure as JSON, with the place of each node; this turns it
 * into straight shapes and lines on the page. The text inside the nodes is typed text, laid out by the app.
 */
data class InkDiagram(val nodes: List<Node>, val edges: List<Edge>) {

    enum class Shape(val code: String) {
        BOX("rectangulo"), ELLIPSE("elipse"), DIAMOND("rombo"),

        /** Text on its own, such as a label next to a line: no outline. */
        NONE("ninguna"),
        ;

        companion object {
            fun ofCode(code: String?): Shape = values().firstOrNull { it.code == code?.lowercase() } ?: BOX
        }
    }

    /** Which ends of a line have an arrowhead. */
    enum class Arrow(val code: String) {
        NONE("ninguna"), END("fin"), BOTH("ambas");

        companion object {
            fun ofCode(code: String?): Arrow = values().firstOrNull { it.code == code?.lowercase() } ?: END
        }
    }

    data class Node(val id: String, val text: String, val shape: Shape, val box: InkRect) {
        val centerX: Float get() = (box.left + box.right) / 2
        val centerY: Float get() = (box.top + box.bottom) / 2
    }

    data class Edge(val from: String, val to: String, val arrow: Arrow, val text: String)

    /**
     * Nodes that are nearly in line are put exactly in line: centres within [ALIGN] units of each other, across or
     * down, move to their average. That is most of what makes a drawn diagram look clean.
     */
    fun aligned(): InkDiagram {
        fun snap(values: List<Float>): List<Float> {
            val order = values.indices.sortedBy { values[it] }
            val result = values.toMutableList()
            var start = 0
            while (start < order.size) {
                var end = start
                while (end + 1 < order.size && values[order[end + 1]] - values[order[end]] <= ALIGN) end++
                val group = order.subList(start, end + 1)
                val average = group.map { values[it] }.average().toFloat()
                for (i in group) result[i] = average
                start = end + 1
            }
            return result
        }
        val xs = snap(nodes.map { it.centerX })
        val ys = snap(nodes.map { it.centerY })
        return copy(
            nodes = nodes.mapIndexed { i, node ->
                val dx = xs[i] - node.centerX
                val dy = ys[i] - node.centerY
                node.copy(box = InkRect(node.box.left + dx, node.box.top + dy, node.box.right + dx, node.box.bottom + dy))
            },
        )
    }

    /**
     * The outlines of the nodes and the lines between them as strokes of [width] and [color], each with its own
     * start time from [startedAt] on. Lines run from centre to centre, cut where they meet each shape with a small
     * gap, and end in arrowheads where the edge says.
     */
    fun strokes(color: Int, width: Float, startedAt: Long): List<Stroke> {
        val lines = ArrayList<List<InkPoint>>()
        for (node in nodes) outline(node)?.let(lines::add)
        val byId = nodes.associateBy { it.id }
        for (edge in edges) {
            val from = byId[edge.from] ?: continue
            val to = byId[edge.to] ?: continue
            val (start, end) = connection(from, to, width) ?: continue
            lines += listOf(start, end)
            val head = max(ARROW_MIN, width * ARROW_PER_WIDTH)
            if (edge.arrow != Arrow.NONE) lines += arrowhead(start, end, head)
            if (edge.arrow == Arrow.BOTH) lines += arrowhead(end, start, head)
        }
        return lines.mapIndexed { i, points -> Stroke(color, width, startedAt + i, points) }
    }

    /** Where an edge's text goes: the middle of its line. Null if the edge does not join two nodes. */
    fun labelPoint(edge: Edge): Pair<Float, Float>? {
        val from = nodes.firstOrNull { it.id == edge.from } ?: return null
        val to = nodes.firstOrNull { it.id == edge.to } ?: return null
        return (from.centerX + to.centerX) / 2 to (from.centerY + to.centerY) / 2
    }

    companion object {
        /** Centres closer than this, in page units, are put in line. */
        const val ALIGN = 10f

        /** Room between a line's end and the shape it meets. */
        const val GAP = 3f

        const val ARROW_MIN = 8f
        const val ARROW_PER_WIDTH = 3f
        private const val ARROW_ANGLE = PI / 7
        private const val ELLIPSE_POINTS = 64

        /**
         * The diagram in the AI's [json] (docs/design/ai.md#clean-diagrams), with each node's `caja` in thousandths of
         * the image of [frame]; null if there is no diagram. Nodes without an id or a box are skipped, and so are
         * edges that do not join two different nodes.
         */
        fun parse(json: String, frame: InkFrame): InkDiagram? {
            val root = try {
                JsonReader(json.trim()).readDocument() as? Map<*, *>
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
            val nodes = (root["nodos"] as? List<*>).orEmpty().mapNotNull { item ->
                val node = item as? Map<*, *> ?: return@mapNotNull null
                val id = node["id"]?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val box = (node["caja"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size == 4 } ?: return@mapNotNull null
                Node(id, (node["texto"] as? String).orEmpty().trim(), Shape.ofCode(node["forma"] as? String), frame.fromThousandths(box[0], box[1], box[2], box[3]))
            }.distinctBy { it.id }
            if (nodes.isEmpty()) return null
            val ids = nodes.mapTo(HashSet()) { it.id }
            val edges = (root["conexiones"] as? List<*>).orEmpty().mapNotNull { item ->
                val edge = item as? Map<*, *> ?: return@mapNotNull null
                val from = edge["desde"]?.toString() ?: return@mapNotNull null
                val to = edge["hasta"]?.toString() ?: return@mapNotNull null
                if (from == to || from !in ids || to !in ids) return@mapNotNull null
                Edge(from, to, Arrow.ofCode(edge["flecha"] as? String), (edge["texto"] as? String).orEmpty().trim())
            }
            return InkDiagram(nodes, edges)
        }

        private fun outline(node: Node): List<InkPoint>? {
            val b = node.box
            return when (node.shape) {
                Shape.NONE -> null
                Shape.BOX -> sharp(listOf(p(b.left, b.top), p(b.right, b.top), p(b.right, b.bottom), p(b.left, b.bottom), p(b.left, b.top)))
                Shape.DIAMOND -> {
                    val cx = node.centerX
                    val cy = node.centerY
                    sharp(listOf(p(cx, b.top), p(b.right, cy), p(cx, b.bottom), p(b.left, cy), p(cx, b.top)))
                }
                Shape.ELLIPSE -> (0..ELLIPSE_POINTS).map { i ->
                    val a = 2 * PI * i / ELLIPSE_POINTS
                    p(node.centerX + b.width / 2 * cos(a).toFloat(), node.centerY + b.height / 2 * sin(a).toFloat())
                }
            }
        }

        /** The line between the shapes of [from] and [to], cut at each edge; null if they overlap. */
        private fun connection(from: Node, to: Node, width: Float): Pair<InkPoint, InkPoint>? {
            val dx = to.centerX - from.centerX
            val dy = to.centerY - from.centerY
            val length = hypot(dx, dy)
            if (length == 0f) return null
            val gap = GAP + width
            val leave = reach(from, dx, dy) + gap / length
            val arrive = 1f - reach(to, -dx, -dy) - gap / length
            if (leave >= arrive) return null
            return p(from.centerX + dx * leave, from.centerY + dy * leave) to p(from.centerX + dx * arrive, from.centerY + dy * arrive)
        }

        /** How far along ([dx], [dy]) from the centre of [node] its outline is, as a fraction of that vector. */
        private fun reach(node: Node, dx: Float, dy: Float): Float {
            val a = node.box.width / 2
            val b = node.box.height / 2
            val x = abs(dx)
            val y = abs(dy)
            return when (node.shape) {
                Shape.BOX, Shape.NONE -> min(if (x > 0f) a / x else Float.MAX_VALUE, if (y > 0f) b / y else Float.MAX_VALUE)
                Shape.ELLIPSE -> 1f / sqrt((x / max(a, 0.01f)).let { it * it } + (y / max(b, 0.01f)).let { it * it })
                Shape.DIAMOND -> 1f / (x / max(a, 0.01f) + y / max(b, 0.01f))
            }
        }

        /** The two wings and the tip of an arrowhead at [tip], for a line coming from [tail]. */
        private fun arrowhead(tail: InkPoint, tip: InkPoint, size: Float): List<InkPoint> {
            val angle = kotlin.math.atan2((tail.y - tip.y).toDouble(), (tail.x - tip.x).toDouble())
            fun wing(turn: Double) = p(tip.x + size * cos(angle + turn).toFloat(), tip.y + size * sin(angle + turn).toFloat())
            return sharp(listOf(wing(ARROW_ANGLE), tip, wing(-ARROW_ANGLE)))
        }

        /**
         * [corners] with each point twice. Strokes are drawn smoothed through the midpoints of their points
         * (docs/design/ink.md#drawing), which rounds a corner; with every point doubled, each curve's control point
         * lies on its own straight segment, so the edges stay straight and the corners sharp.
         */
        fun sharp(corners: List<InkPoint>): List<InkPoint> = corners.flatMap { listOf(it, it) }

        private fun p(x: Float, y: Float) = InkPoint(x, y, 0)
    }
}
