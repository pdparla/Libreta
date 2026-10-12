package com.parla.libreta.core.note

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteSearchTest {

    private val notes = listOf(
        SearchableNote(NotePath("Médico.md"), "Médico", "Cita el martes"),
        SearchableNote(NotePath("Compra.md"), "Compra", "", pages = listOf("Lista", "Mañana compraré pan, leche y seis huevos y llamaré a Ana")),
        SearchableNote(NotePath("Trabajo.md"), "Trabajo", "Llamar a Ana por el presupuesto"),
    )

    @Test
    fun `ignores case and accents`() {
        assertEquals(listOf(NotePath("Médico.md")), NoteSearch.search(notes, "MEDICO").map { it.path })
        assertEquals(listOf(NotePath("Compra.md")), NoteSearch.search(notes, "compraré").map { it.path })
    }

    @Test
    fun `says where it found the text, the handwriting page included`() {
        val hits = NoteSearch.search(notes, "ana")

        assertEquals(listOf(NotePath("Compra.md"), NotePath("Trabajo.md")), hits.map { it.path })
        assertEquals(SearchHit.Where.PAGE, hits[0].where)
        assertEquals(1, hits[0].page)
        assertEquals(SearchHit.Where.TEXT, hits[1].where)
        assertEquals(SearchHit.Where.TITLE, NoteSearch.search(notes, "trabajo")[0].where)
    }

    @Test
    fun `the snippet is the text around the match`() {
        val hit = NoteSearch.search(notes, "huevos").single()

        assertEquals("Mañana compraré pan, leche y seis huevos y llamaré a Ana", hit.snippet)
        val long = SearchableNote(NotePath("L.md"), "L", "x".repeat(100) + " aguja " + "y".repeat(100))
        val snippet = NoteSearch.search(listOf(long), "aguja").single().snippet
        assertEquals(true, snippet.startsWith("…") && snippet.endsWith("…") && "aguja" in snippet)
    }

    @Test
    fun `an empty query finds nothing`() = assertEquals(emptyList<SearchHit>(), NoteSearch.search(notes, "  "))

    @Test
    fun `normalize gives for every character what the NFD algorithm gave`() {
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            val base = if (code < 128) c else Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
            val expected = base.lowercaseChar().toString()
            assertEquals("U+%04X".format(code), expected, NoteSearch.normalize(c.toString()))
            // Asked again, from the cache.
            assertEquals("U+%04X".format(code), expected, NoteSearch.normalize(c.toString()))
        }
    }

    @Test
    fun `normalized forms do not take part in equality and a search finds the same again`() {
        val a = SearchableNote(NotePath("A.md"), "Árbol", "Sombra")
        val b = a.copy()
        NoteSearch.search(listOf(a), "arbol")

        assertEquals(b, a)
        assertEquals(b.hashCode(), a.hashCode())
        assertEquals(NoteSearch.search(listOf(b), "sombra"), NoteSearch.search(listOf(a), "sombra"))
    }
}
