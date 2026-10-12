package com.parla.libreta.core.ink

/**
 * Stores an [InkDocument] as JSON (`libreta.ink.json`):
 *
 * ```json
 * {"format":"libreta-ink","version":2,"pages":[{"width":600,"height":900,"paper":"lined","strokes":[
 *   {"color":"#ff202124","width":3.5,"start":1760197500000,"points":[12.5,40,0, 13,41.5,16, ...]},
 *   {"color":"#ffffeb3b","width":7.5,"start":1760197600000,"fill":true,"points":[100,201,0, 180,201,0]}],
 *   "marks":[{"kind":"title","text":"Viaje","strokes":[1760197500000]}]}]}
 * ```
 *
 * Points are a flat array of `x, y, t` triples to keep files small; coordinates are rounded to 0.1 units. `fill`
 * (docs/design/ink.md) is only written when true, so pages without fills keep their fingerprint. A
 * hand-written codec, because org.json is only a stub in JVM tests and bundling another copy would clash with the
 * one in Android itself.
 */
object InkJson {

    const val FORMAT = "libreta-ink"
    const val VERSION = 2

    /** Without marks a document is written exactly as before marks existed, so its fingerprints do not change. */
    private const val VERSION_WITHOUT_MARKS = 1

    fun encode(document: InkDocument): String {
        var estimate = 64
        for (page in document.pages) {
            estimate += 96
            for (stroke in page.strokes) estimate += 96 + stroke.points.size * CHARS_PER_POINT
        }
        val out = StringBuilder(estimate)
        val version = if (document.pages.any { it.marks.isNotEmpty() }) VERSION else VERSION_WITHOUT_MARKS
        out.append("{\"format\":\"").append(FORMAT).append("\",\"version\":").append(version).append(",\"pages\":[")
        for ((p, page) in document.pages.withIndex()) {
            if (p > 0) out.append(',')
            out.append("{\"width\":").append(page.width).append(",\"height\":").append(page.height)
            out.append(",\"paper\":\"").append(page.paper.code).append("\",\"strokes\":[")
            for ((s, stroke) in page.strokes.withIndex()) {
                if (s > 0) out.append(',')
                out.append("{\"color\":\"#")
                appendHex(out, stroke.color)
                out.append("\",\"width\":")
                appendNumber(out, stroke.width)
                out.append(",\"start\":").append(stroke.startedAt).append(',')
                if (stroke.fill) out.append("\"fill\":true,")
                out.append("\"points\":[")
                var first = true
                for (point in stroke.points) {
                    if (!first) out.append(',')
                    first = false
                    appendNumber(out, point.x)
                    out.append(',')
                    appendNumber(out, point.y)
                    out.append(',').append(point.t)
                }
                out.append("]}")
            }
            out.append("]")
            if (page.marks.isNotEmpty()) {
                out.append(",\"marks\":[")
                page.marks.forEachIndexed { m, mark ->
                    if (m > 0) out.append(',')
                    out.append("{\"kind\":\"").append(mark.kind.code).append("\",\"text\":").append(jsonString(mark.text))
                    out.append(",\"strokes\":[").append(mark.strokes.joinToString(",")).append("]}")
                }
                out.append("]")
            }
            out.append("}")
        }
        out.append("]}")
        return out.toString()
    }

    /** @throws IllegalArgumentException if [json] is not a valid ink document. */
    fun decode(json: String): InkDocument {
        val root = JsonReader(json).readDocument() as? Map<*, *> ?: invalid("not an object")
        if (root["format"] != FORMAT) invalid("unknown format ${root["format"]}")
        val version = (root["version"] as? Double)?.toInt() ?: invalid("missing version")
        if (version > VERSION) invalid("version $version is newer than this app")
        val pages = (root["pages"] as? List<*> ?: invalid("missing pages")).map { pageJson ->
            val page = pageJson as? Map<*, *> ?: invalid("page is not an object")
            InkPage(
                width = number(page, "width").toInt(),
                height = number(page, "height").toInt(),
                paper = Paper.ofCode(page["paper"] as? String ?: ""),
                strokes = (page["strokes"] as? List<*> ?: emptyList<Any>()).map(::decodeStroke),
                marks = (page["marks"] as? List<*>).orEmpty().mapNotNull(::decodeMark),
            )
        }
        return InkDocument(pages)
    }

    /** A mark (version 2); an unknown kind is skipped rather than failing the whole note. */
    private fun decodeMark(json: Any?): InkMark? {
        val mark = json as? Map<*, *> ?: invalid("mark is not an object")
        val kind = InkMark.Kind.values().firstOrNull { it.code == mark["kind"] } ?: return null
        val strokes = (mark["strokes"] as? List<*>).orEmpty().mapNotNull { (it as? Double)?.toLong() }.toCollection(LinkedHashSet())
        return InkMark(kind, mark["text"] as? String ?: "", strokes)
    }

    private fun decodeStroke(json: Any?): Stroke {
        val stroke = json as? Map<*, *> ?: invalid("stroke is not an object")
        val flat = stroke["points"] as? List<*> ?: invalid("missing points")
        if (flat.isEmpty() || flat.size % 3 != 0) invalid("points must be x,y,t triples")
        val points = ArrayList<InkPoint>(flat.size / 3)
        if (flat is NumberArray) {
            // Straight from the primitives, without a box per number.
            val v = flat.values
            for (i in 0 until flat.size step 3) points.add(InkPoint(v[i].toFloat(), v[i + 1].toFloat(), v[i + 2].toInt()))
        } else {
            for (i in flat.indices step 3) {
                points.add(
                    InkPoint(
                        x = (flat[i] as? Double ?: invalid("bad x")).toFloat(),
                        y = (flat[i + 1] as? Double ?: invalid("bad y")).toFloat(),
                        t = (flat[i + 2] as? Double ?: invalid("bad t")).toInt(),
                    ),
                )
            }
        }
        return Stroke(
            color = hexToColor(stroke["color"] as? String ?: invalid("missing color")),
            width = number(stroke, "width").toFloat(),
            startedAt = number(stroke, "start").toLong(),
            points = points,
            fill = stroke["fill"] == true,
        )
    }

    private fun number(json: Map<*, *>, key: String): Double = json[key] as? Double ?: invalid("missing $key")

    /**
     * Appends the shortest form with at most one decimal: 12.0 -> "12", 12.34 -> "12.3", -0.5 -> "-0.5". Written
     * digit by digit; magnitudes from 1e7 on, where `Double.toString` turns scientific, take the old way.
     */
    private fun appendNumber(out: StringBuilder, value: Float) {
        val tenths = Math.round(value * 10.0)
        if (tenths <= -PLAIN_LIMIT || tenths >= PLAIN_LIMIT) {
            out.append(if (tenths % 10 == 0L) (tenths / 10).toString() else (tenths / 10.0).toString())
            return
        }
        var magnitude = tenths.toInt()
        if (magnitude < 0) {
            out.append('-')
            magnitude = -magnitude
        }
        out.append(magnitude / 10)
        val decimal = magnitude % 10
        if (decimal != 0) out.append('.').append(('0'.code + decimal).toChar())
    }

    /** [color] as 8 lowercase hex digits (ARGB). */
    private fun appendHex(out: StringBuilder, color: Int) {
        for (shift in 28 downTo 0 step 4) out.append(HEX_DIGITS[(color ushr shift) and 0xf])
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Tenths below this (1e7 units) print plainly in `Double.toString`. */
    private const val PLAIN_LIMIT = 100_000_000L

    /** A generous guess at the size of one point (`123.4,567.8,12345,`), to size the output once. */
    private const val CHARS_PER_POINT = 18

    private fun hexToColor(hex: String): Int {
        val digits = hex.removePrefix("#")
        val value = digits.toLongOrNull(16) ?: invalid("bad color $hex")
        return when (digits.length) {
            6 -> (0xff000000L or value).toInt()
            8 -> value.toInt()
            else -> invalid("bad color $hex")
        }
    }

    private fun invalid(reason: String): Nothing = throw IllegalArgumentException("Invalid ink document: $reason")
}
