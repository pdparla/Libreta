package com.parla.libreta.core.ink

/**
 * Stores an [InkDocument] as JSON (`libreta.ink.json`):
 *
 * ```json
 * {"format":"libreta-ink","version":1,"pages":[{"width":600,"height":900,"paper":"lined","strokes":[
 *   {"color":"#ff202124","width":3.5,"start":1760197500000,"points":[12.5,40,0, 13,41.5,16, ...]}]}]}
 * ```
 *
 * Points are a flat array of `x, y, t` triples to keep files small; coordinates are rounded to 0.1 units. A
 * hand-written codec, because org.json is only a stub in JVM tests and bundling another copy would clash with the
 * one in Android itself.
 */
object InkJson {

    const val FORMAT = "libreta-ink"
    const val VERSION = 1

    fun encode(document: InkDocument): String = buildString {
        append("""{"format":"$FORMAT","version":$VERSION,"pages":[""")
        document.pages.forEachIndexed { p, page ->
            if (p > 0) append(',')
            append("""{"width":${page.width},"height":${page.height},"paper":"${page.paper.code}","strokes":[""")
            page.strokes.forEachIndexed { s, stroke ->
                if (s > 0) append(',')
                append("""{"color":"${colorToHex(stroke.color)}","width":${number(stroke.width)},"start":${stroke.startedAt},"points":[""")
                stroke.points.forEachIndexed { i, point ->
                    if (i > 0) append(',')
                    append(number(point.x)).append(',').append(number(point.y)).append(',').append(point.t)
                }
                append("]}")
            }
            append("]}")
        }
        append("]}")
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
            )
        }
        return InkDocument(pages)
    }

    private fun decodeStroke(json: Any?): Stroke {
        val stroke = json as? Map<*, *> ?: invalid("stroke is not an object")
        val flat = stroke["points"] as? List<*> ?: invalid("missing points")
        if (flat.isEmpty() || flat.size % 3 != 0) invalid("points must be x,y,t triples")
        val points = (flat.indices step 3).map { i ->
            InkPoint(
                x = (flat[i] as? Double ?: invalid("bad x")).toFloat(),
                y = (flat[i + 1] as? Double ?: invalid("bad y")).toFloat(),
                t = (flat[i + 2] as? Double ?: invalid("bad t")).toInt(),
            )
        }
        return Stroke(
            color = hexToColor(stroke["color"] as? String ?: invalid("missing color")),
            width = number(stroke, "width").toFloat(),
            startedAt = number(stroke, "start").toLong(),
            points = points,
        )
    }

    private fun number(json: Map<*, *>, key: String): Double = json[key] as? Double ?: invalid("missing $key")

    /** Shortest form with at most one decimal: 12.0 -> "12", 12.34 -> "12.3". */
    private fun number(value: Float): String {
        val tenths = Math.round(value * 10.0)
        return if (tenths % 10 == 0L) (tenths / 10).toString() else (tenths / 10.0).toString()
    }

    private fun colorToHex(color: Int): String = "#" + (color.toLong() and 0xffffffffL).toString(16).padStart(8, '0')

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
