package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The codec as it was before unboxed reading and digit-by-digit writing, kept to prove the new one is identical. */
private object OldInkJson {
    fun encode(document: InkDocument): String = buildString {
        val version = if (document.pages.any { it.marks.isNotEmpty() }) 2 else 1
        append("""{"format":"${InkJson.FORMAT}","version":$version,"pages":[""")
        document.pages.forEachIndexed { p, page ->
            if (p > 0) append(',')
            append("""{"width":${page.width},"height":${page.height},"paper":"${page.paper.code}","strokes":[""")
            page.strokes.forEachIndexed { s, stroke ->
                if (s > 0) append(',')
                append("""{"color":"${colorToHex(stroke.color)}","width":${number(stroke.width)},"start":${stroke.startedAt},""")
                if (stroke.fill) append("\"fill\":true,")
                append("\"points\":[")
                stroke.points.forEachIndexed { i, point ->
                    if (i > 0) append(',')
                    append(number(point.x)).append(',').append(number(point.y)).append(',').append(point.t)
                }
                append("]}")
            }
            append("]")
            if (page.marks.isNotEmpty()) {
                append(",\"marks\":[")
                page.marks.forEachIndexed { m, mark ->
                    if (m > 0) append(',')
                    append("{\"kind\":\"").append(mark.kind.code).append("\",\"text\":").append(jsonString(mark.text))
                    append(",\"strokes\":[").append(mark.strokes.joinToString(",")).append("]}")
                }
                append("]")
            }
            append("}")
        }
        append("]}")
    }

    /** @throws IllegalArgumentException if [json] is not a valid ink document. */
    fun decode(json: String): InkDocument {
        val root = OldJsonReader(json).readDocument() as? Map<*, *> ?: oldInvalid("not an object")
        if (root["format"] != InkJson.FORMAT) oldInvalid("unknown format ${root["format"]}")
        val version = (root["version"] as? Double)?.toInt() ?: oldInvalid("missing version")
        if (version > InkJson.VERSION) oldInvalid("version $version is newer than this app")
        val pages = (root["pages"] as? List<*> ?: oldInvalid("missing pages")).map { pageJson ->
            val page = pageJson as? Map<*, *> ?: oldInvalid("page is not an object")
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
        val mark = json as? Map<*, *> ?: oldInvalid("mark is not an object")
        val kind = InkMark.Kind.values().firstOrNull { it.code == mark["kind"] } ?: return null
        val strokes = (mark["strokes"] as? List<*>).orEmpty().mapNotNull { (it as? Double)?.toLong() }.toCollection(LinkedHashSet())
        return InkMark(kind, mark["text"] as? String ?: "", strokes)
    }

    private fun decodeStroke(json: Any?): Stroke {
        val stroke = json as? Map<*, *> ?: oldInvalid("stroke is not an object")
        val flat = stroke["points"] as? List<*> ?: oldInvalid("missing points")
        if (flat.isEmpty() || flat.size % 3 != 0) oldInvalid("points must be x,y,t triples")
        val points = (flat.indices step 3).map { i ->
            InkPoint(
                x = (flat[i] as? Double ?: oldInvalid("bad x")).toFloat(),
                y = (flat[i + 1] as? Double ?: oldInvalid("bad y")).toFloat(),
                t = (flat[i + 2] as? Double ?: oldInvalid("bad t")).toInt(),
            )
        }
        return Stroke(
            color = hexToColor(stroke["color"] as? String ?: oldInvalid("missing color")),
            width = number(stroke, "width").toFloat(),
            startedAt = number(stroke, "start").toLong(),
            points = points,
            fill = stroke["fill"] == true,
        )
    }

    private fun number(json: Map<*, *>, key: String): Double = json[key] as? Double ?: oldInvalid("missing $key")

    /** Shortest form with at most one decimal: 12.0 -> "12", 12.34 -> "12.3". */
    private fun number(value: Float): String {
        val tenths = Math.round(value * 10.0)
        return if (tenths % 10 == 0L) (tenths / 10).toString() else (tenths / 10.0).toString()
    }

    private fun colorToHex(color: Int): String = "#" + (color.toLong() and 0xffffffffL).toString(16).padStart(8, '0')

    private fun hexToColor(hex: String): Int {
        val digits = hex.removePrefix("#")
        val value = digits.toLongOrNull(16) ?: oldInvalid("bad color $hex")
        return when (digits.length) {
            6 -> (0xff000000L or value).toInt()
            8 -> value.toInt()
            else -> oldInvalid("bad color $hex")
        }
    }

}

private fun oldInvalid(reason: String): Nothing = throw IllegalArgumentException("Invalid: $reason")

/** Copy of the reader before unboxed number arrays. */
private class OldJsonReader(private val text: String) {
    private var pos = 0

    fun readDocument(): Any? {
        val value = readValue()
        skipWhitespace()
        if (pos != text.length) oldInvalid("trailing characters at $pos")
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        if (pos >= text.length) oldInvalid("unexpected end")
        return when (val c = text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) readNumber() else oldInvalid("unexpected '$c' at $pos")
        }
    }

    private fun readObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        pos++ // {
        skipWhitespace()
        if (peek() == '}') return result.also { pos++ }
        while (true) {
            skipWhitespace()
            if (peek() != '"') oldInvalid("expected key at $pos")
            val key = readString()
            skipWhitespace()
            expect(':')
            result[key] = readValue()
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return result
                else -> oldInvalid("expected , or } at ${pos - 1}")
            }
        }
    }

    private fun readArray(): List<Any?> {
        val result = ArrayList<Any?>()
        pos++ // [
        skipWhitespace()
        if (peek() == ']') return result.also { pos++ }
        while (true) {
            result += readValue()
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return result
                else -> oldInvalid("expected , or ] at ${pos - 1}")
            }
        }
    }

    private fun readString(): String {
        pos++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) oldInvalid("unterminated string")
            when (val c = text[pos++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) oldInvalid("bad escape")
                    when (val e = text[pos++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) oldInvalid("bad unicode escape")
                            out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> oldInvalid("bad escape \\$e")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun readNumber(): Double {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        return text.substring(start, pos).toDoubleOrNull() ?: oldInvalid("bad number at $start")
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!text.startsWith(word, pos)) oldInvalid("unexpected token at $pos")
        pos += word.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else oldInvalid("unexpected end")

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
        if (next() != c) oldInvalid("expected '$c' at ${pos - 1}")
    }
}


class InkJsonCodecTest {

    private fun randomCoordinate(random: Random): Float = when (random.nextInt(8)) {
        0 -> random.nextInt(2000).toFloat()                            // whole numbers
        1 -> random.nextInt(20000) / 10f                                // one decimal
        2 -> (random.nextFloat() - 0.5f) * 3000f                        // negatives, many decimals
        3 -> (random.nextFloat() - 0.5f) * 1.2f                         // around zero: -0.0x, -0.5, 0.04
        4 -> random.nextInt(100_000_000) / 10f * (if (random.nextBoolean()) 1 else -1) // up to the plain limit
        5 -> (random.nextFloat() - 0.5f) * 4e8f                         // past 1e7: scientific in Double.toString
        6 -> (random.nextInt(9) - 4) * 0.05f + 0.05f * random.nextInt(2) // rounding ties
        else -> random.nextFloat() * 1e4f
    }

    private fun randomDocument(random: Random, pages: Int, strokes: Int): InkDocument = InkDocument(
        (0 until pages).map { p ->
            InkPage(
                width = 300 + random.nextInt(900), height = 300 + random.nextInt(900),
                paper = Paper.values()[random.nextInt(Paper.values().size)],
                strokes = (0 until strokes).map {
                    Stroke(
                        color = random.nextInt(), width = if (random.nextInt(4) == 0) random.nextInt(60).toFloat() else randomCoordinate(random),
                        startedAt = 1_760_197_500_000 + random.nextInt(1_000_000), fill = random.nextInt(5) == 0,
                        points = (0 until 1 + random.nextInt(60)).map { InkPoint(randomCoordinate(random), randomCoordinate(random), random.nextInt(200_000) - 100) },
                    )
                },
                marks = if (p == 0 && random.nextBoolean()) listOf(InkMark(InkMark.Kind.TITLE, "t \"x\" é", linkedSetOf(1_760_197_500_001))) else emptyList(),
            )
        },
    )

    @Test
    fun `random documents encode exactly as the old encoder and decode to the same document`() {
        val random = Random(20261012)
        repeat(60) {
            val doc = randomDocument(random, pages = 1 + random.nextInt(3), strokes = 1 + random.nextInt(25))
            val encoded = InkJson.encode(doc)
            assertEquals(OldInkJson.encode(doc), encoded)
            assertEquals(OldInkJson.decode(encoded), InkJson.decode(encoded))
        }
    }

    @Test
    fun `numbers are written as the old encoder wrote them`() {
        val values = ArrayList<Float>()
        for (n in -200_000..200_000) values += n / 10f
        val random = Random(7)
        repeat(300_000) { values += (random.nextInt(200_000_000) - 100_000_000) / 10f }
        values += listOf(0f, -0f, 0.04f, -0.04f, 0.05f, -0.05f, 0.06f, 9_999_999.9f, -9_999_999.9f, 1e7f, -1e7f, 1.6e7f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, Float.MIN_VALUE)
        for (chunk in values.chunked(30)) {
            val doc = InkDocument(listOf(InkPage(600, 900, Paper.LINED, listOf(Stroke(0, chunk[0], 0, chunk.map { InkPoint(it, -it, 0) })))))
            assertEquals(OldInkJson.encode(doc), InkJson.encode(doc))
        }
    }

    @Test
    fun `colours are written as 8 lowercase hex digits`() {
        for (color in listOf(0, 1, -1, 0xff202124.toInt(), 0x00000abc, Int.MIN_VALUE, Int.MAX_VALUE, 0x0f0f0f0f)) {
            val doc = InkDocument(listOf(InkPage(10, 10, Paper.LINED, listOf(Stroke(color, 1f, 5, listOf(InkPoint(1f, 2f, 3)))))))
            assertEquals(OldInkJson.encode(doc), InkJson.encode(doc))
        }
    }

    private fun parse(token: String): Any? = (JsonReader("[$token]").readDocument() as List<*>)[0]

    private fun parseOrNull(token: String): Double? = try { parse(token) as Double } catch (e: IllegalArgumentException) { null }

    @Test
    fun `numbers read give the same double as String toDouble`() {
        for (n in 0..3_000_000) {
            val tenths = "${n / 10}.${n % 10}"
            assertEquals(tenths, tenths.toDouble().toRawBits(), (parse(tenths) as Double).toRawBits())
            assertEquals("-$tenths", "-$tenths".toDouble().toRawBits(), (parse("-$tenths") as Double).toRawBits())
            assertEquals(n.toString().toDouble().toRawBits(), (parse(n.toString()) as Double).toRawBits())
        }
        val random = Random(99)
        val digits = "0123456789"
        repeat(300_000) {
            val sb = StringBuilder()
            if (random.nextInt(4) == 0) sb.append('-')
            repeat(1 + random.nextInt(18)) { sb.append(digits[random.nextInt(10)]) }
            if (random.nextInt(2) == 0) {
                sb.append('.')
                repeat(random.nextInt(18)) { sb.append(digits[random.nextInt(10)]) }
            }
            if (random.nextInt(8) == 0) sb.append(if (random.nextBoolean()) "e" else "E").append(random.nextInt(60) - 30)
            val token = sb.toString()
            assertEquals(token, token.toDoubleOrNull()?.toRawBits(), parseOrNull(token)?.toRawBits())
        }
        for (token in listOf("0", "-0", "-0.0", "0.1", "007", "1.", "-", "1e5", "1E+5", "1.5e-3", "999999999999999", "9999999999999999", "123456789012345.6", "0.30000000000000004", "1760197500000", "-1760197500000")) {
            assertEquals(token, token.toDoubleOrNull()?.toRawBits(), parseOrNull(token)?.toRawBits())
        }
    }

    @Test
    fun `arrays of numbers are primitive lists and mixed arrays stay ordinary lists`() {
        val numbers = parse("[1, 2.5, -3, 1760197500000]") as List<*>
        assertTrue(numbers is NumberArray)
        assertEquals(listOf(1.0, 2.5, -3.0, 1760197500000.0), numbers)
        assertEquals(emptyList<Any>(), parse("[]"))
        assertEquals(listOf(1.0, "a", 2.0, null, true), parse("[1, \"a\", 2, null, true]"))
        assertEquals(listOf("a", 1.0), parse("[\"a\", 1]"))
        assertEquals(listOf(listOf(1.0, 2.0), listOf<Any>()), parse("[[1,2],[]]"))
        assertEquals(OldJsonReader("[1,2,3]").readDocument(), JsonReader("[1,2,3]").readDocument())
        for (bad in listOf("[1,]", "[1 2]", "[1,2", "[-]", "[1,\"a\",]")) {
            assertTrue(bad, runCatching { JsonReader(bad).readDocument() }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `a stroke with a non-number in its points is still rejected`() {
        val json = """{"format":"libreta-ink","version":1,"pages":[{"width":1,"height":1,"paper":"lined","strokes":[{"color":"#ff000000","width":1,"start":1,"points":[1,"a",3]}]}]}"""
        assertTrue(runCatching { InkJson.decode(json) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { OldInkJson.decode(json) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `big note timing, new against old`() {
        val random = Random(1)
        val doc = InkDocument(listOf(InkPage(600, 900, Paper.LINED, (0 until 100).map {
            Stroke(0xff202124.toInt(), 3f, 1_760_197_500_000, (0 until 500).map { i -> InkPoint(random.nextInt(6000) / 10f, random.nextInt(9000) / 10f, i * 8) })
        })))
        val json = InkJson.encode(doc)
        repeat(3) { OldInkJson.encode(doc); InkJson.encode(doc); OldInkJson.decode(json); InkJson.decode(json) }
        fun time(block: () -> Unit): Long { val t = System.nanoTime(); repeat(5) { block() }; return (System.nanoTime() - t) / 5_000_000 }
        val oldEncode = time { OldInkJson.encode(doc) }; val newEncode = time { InkJson.encode(doc) }
        val oldDecode = time { OldInkJson.decode(json) }; val newDecode = time { InkJson.decode(json) }
        println("50000 points: encode old ${oldEncode}ms new ${newEncode}ms; decode old ${oldDecode}ms new ${newDecode}ms")
        assertEquals(OldInkJson.decode(json), InkJson.decode(json))
    }
}
