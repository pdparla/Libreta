package com.parla.libreta.core.ink

/**
 * Hand-written JSON for the files kept next to a handwritten note (`libreta.ink.json`, `libreta.text.json`): org.json
 * is only a stub in JVM tests, and bundling another copy would clash with the one in Android itself.
 */

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

    private fun readNumber(): Double {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        return text.substring(start, pos).toDoubleOrNull() ?: invalid("bad number at $start")
    }

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
