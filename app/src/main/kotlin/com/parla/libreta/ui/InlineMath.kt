package com.parla.libreta.ui

/**
 * Where a formula between single dollars starts and ends, as "clean copy" and the AI write them (`$x^2$`), with
 * Pandoc's rule so that prices do not turn into formulas: the opening `$` is followed by something other than a
 * space or another `$`, and the closing one is preceded by something other than a space and not followed by a
 * digit. `5 $ y 10 $`, `$5 y $10` and `$$` are text. A `\$` inside does not close it.
 */
object InlineMath {

    /** The formula opening at [start] (which must be a `$`): the LaTeX and the index just past the closing `$`. */
    fun at(input: String, start: Int): Pair<String, Int>? {
        if (input.getOrNull(start) != '$') return null
        val first = input.getOrNull(start + 1) ?: return null
        if (first == '$' || first.isWhitespace()) return null
        var i = start + 1
        while (i < input.length) {
            when (input[i]) {
                '\\' -> i++
                '\n' -> if (input.getOrNull(i + 1) == '\n') return null
                '$' -> {
                    val closes = !input[i - 1].isWhitespace() && input.getOrNull(i + 1)?.isDigit() != true
                    if (closes) return input.substring(start + 1, i) to i + 1
                }
            }
            i++
        }
        return null
    }
}
