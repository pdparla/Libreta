package com.parla.libreta.core.ink

/**
 * Hand-written JSON for the files kept next to a handwritten note (`libreta.ink.json`, `libreta.text.json`): org.json
 * is only a stub in JVM tests, and bundling another copy would clash with the one in Android itself.
 */

/**
 * An array of numbers read without boxing each one: a `List<Double>` for any consumer, plus the primitive [values]
 * (the first [size] are valid) for those that want to avoid the boxes.
 */
internal class NumberArray(val values: DoubleArray, override val size: Int) : AbstractList<Double>() {
    override fun get(index: Int): Double {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index $index, size $size")
        return values[index]
    }
}

private const val INITIAL_NUMBERS = 32
private const val MAX_FAST_DIGITS = 15
private val POWERS_OF_TEN = DoubleArray(MAX_FAST_DIGITS + 1).also { var v = 1.0; for (i in it.indices) { it[i] = v; v *= 10.0 } }

/** Minimal JSON reader: objects, arrays, strings, numbers (as Double), booleans and null. */
internal class JsonReader(private val text: String) {
    private var pos = 0

    fun readDocument(): Any? {
        val value = readValue()
        skipWhitespace()
        if (pos != text.length) invalid("trailing characters at $pos")
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        if (pos >= text.length) invalid("unexpected end")
        return when (val c = text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) readNumber() else invalid("unexpected '$c' at $pos")
        }
    }

    private fun readObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        pos++ // {
        skipWhitespace()
        if (peek() == '}') return result.also { pos++ }
        while (true) {
            skipWhitespace()
            if (peek() != '"') invalid("expected key at $pos")
            val key = readString()
            skipWhitespace()
            expect(':')
            result[key] = readValue()
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return result
                else -> invalid("expected , or } at ${pos - 1}")
            }
        }
    }

    /**
     * An array of only plain numbers comes back as a [NumberArray] (primitive doubles, no boxing per number): the
     * `points` of a big note have tens of thousands of them. Any other array is an ordinary list.
     */
    private fun readArray(): List<Any?> {
        pos++ // [
        skipWhitespace()
        if (peek() == ']') return ArrayList<Any?>().also { pos++ }
        var numbers: DoubleArray? = null
        var count = 0
        var list: ArrayList<Any?>? = null
        while (true) {
            skipWhitespace()
            val c = peek()
            if (list == null && (c == '-' || c.isDigit()) && (numbers != null || count == 0)) {
                val values = numbers ?: DoubleArray(INITIAL_NUMBERS).also { numbers = it }
                val grown = if (count == values.size) values.copyOf(count * 2).also { numbers = it } else values
                grown[count++] = readNumber()
            } else {
                if (list == null) {
                    list = ArrayList(maxOf(count * 2, 8))
                    val values = numbers
                    for (i in 0 until count) list.add(values!![i])
                    numbers = null
                }
                list += readValue()
            }
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return list ?: NumberArray(numbers!!, count)
                else -> invalid("expected , or ] at ${pos - 1}")
            }
        }
    }

    private fun readString(): String {
        pos++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) invalid("unterminated string")
            when (val c = text[pos++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) invalid("bad escape")
                    when (val e = text[pos++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) invalid("bad unicode escape")
                            out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> invalid("bad escape \\$e")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    /**
     * Same value as `String.toDouble()` of the token. Plain decimals of up to 15 digits (`12`, `-12.5`) are computed
     * directly, without a substring: digits / 10^k with both operands exact doubles is a correctly rounded division,
     * which is what the parser itself gives. Anything else (exponents, more digits, odd forms) takes the slow way.
     */
    private fun readNumber(): Double {
        val start = pos
        val len = text.length
        var p = pos
        val negative = text[p] == '-'
        if (negative) p++
        var n = 0L
        var digits = 0
        while (p < len && digits < MAX_FAST_DIGITS) {
            val d = text[p] - '0'
            if (d < 0 || d > 9) break
            n = n * 10 + d
            digits++
            p++
        }
        var fraction = 0
        if (digits > 0 && p < len && text[p] == '.') {
            p++
            while (p < len && digits < MAX_FAST_DIGITS) {
                val d = text[p] - '0'
                if (d < 0 || d > 9) break
                n = n * 10 + d
                digits++
                fraction++
                p++
            }
            if (fraction == 0) p = -1 // "12." and the like: let the parser decide
        }
        if (digits > 0 && p > 0 && (p >= len || !isNumberChar(text[p]))) {
            pos = p
            val magnitude = if (fraction == 0) n.toDouble() else n / POWERS_OF_TEN[fraction]
            return if (negative) -magnitude else magnitude
        }
        pos = start
        while (pos < len && isNumberChar(text[pos])) pos++
        return text.substring(start, pos).toDoubleOrNull() ?: invalid("bad number at $start")
    }

    private fun isNumberChar(c: Char): Boolean = c.isDigit() || c == '+' || c == '-' || c == '.' || c == 'e' || c == 'E'

    private fun literal(word: String, value: Any?): Any? {
        if (!text.startsWith(word, pos)) invalid("unexpected token at $pos")
        pos += word.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else invalid("unexpected end")

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
        if (next() != c) invalid("expected '$c' at ${pos - 1}")
    }
}

/** [text] as a JSON string literal, quotes included. */
internal fun jsonString(text: String): String = buildString {
    append('"')
    for (c in text) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}

private fun invalid(reason: String): Nothing = throw IllegalArgumentException("Invalid JSON: $reason")
