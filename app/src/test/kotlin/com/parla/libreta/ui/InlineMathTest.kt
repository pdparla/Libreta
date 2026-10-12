package com.parla.libreta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineMathTest {

    private fun formula(text: String) = InlineMath.at(text, text.indexOf('$'))?.first

    @Test
    fun `a formula between single dollars`() {
        assertEquals("x^2 + 1", formula("es \$x^2 + 1\$ cuando"))
        assertEquals("\\frac{a}{b}", formula("\$\\frac{a}{b}\$."))
    }

    @Test
    fun `the end is just past the closing dollar`() {
        assertEquals("a" to 3, InlineMath.at("\$a\$ y", 0))
    }

    @Test
    fun `prices are not formulas`() {
        assertNull(formula("cuesta 5 \$ y 10 \$"))
        assertNull(formula("\$5 y \$10"))
        assertNull(formula("de \$ 3 a 4\$"))
    }

    @Test
    fun `a closing dollar followed by a digit does not close`() {
        assertEquals("a\$1 + b", formula("\$a\$1 + b\$"))
    }

    @Test
    fun `double dollars are left to the block and double-dollar parsers`() {
        assertNull(formula("\$\$x\$\$"))
    }

    @Test
    fun `an escaped dollar does not close`() {
        assertEquals("a \\\$ b", formula("\$a \\\$ b\$"))
    }

    @Test
    fun `a formula never spans paragraphs or runs to the end unclosed`() {
        assertNull(formula("\$a\n\nb\$"))
        assertNull(formula("\$abc"))
        assertEquals("a\nb", formula("\$a\nb\$"))
    }
}
