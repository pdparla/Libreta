package com.parla.libreta.core.note

import com.parla.libreta.core.note.NoteLinks.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteLinksTest {

    private fun path(value: String) = NotePath(value)

    @Test
    fun `links with a title, a category or a text to show`() {
        assertEquals(
            listOf(Link("Compra", "Compra"), Link("Casa/Obra", "Obra"), Link("Viaje", "el viaje")),
            NoteLinks.of("Ver [[Compra]], [[Casa/Obra|Obra]] y [[ Viaje | el viaje ]]."),
        )
    }

    @Test
    fun `links in code are text`() {
        val markdown = "`[[Uno]]` y [[Dos]]\n\n```\n[[Tres]]\n```\n[[Cuatro]] ``a [[Cinco]] `b` ``"
        assertEquals(listOf("Dos", "Cuatro"), NoteLinks.of(markdown).map { it.target })
    }

    @Test
    fun `not links`() {
        assertEquals(emptyList<Link>(), NoteLinks.of("[[]] [[a\nb]] [Compra] [[x|]] [x](y)"))
    }

    @Test
    fun `links become Markdown links to the scheme, code stays`() {
        assertEquals(
            "Ver [Compra](<wiki:Compra>) y [Obra](<wiki:Casa/Obra>), `[[Código]]`",
            NoteLinks.toMarkdownLinks("Ver [[Compra]] y [[Casa/Obra|Obra]], `[[Código]]`", "wiki:"),
        )
        val fenced = "a\n```\n[[X]]\n```\nb [[Y]]"
        assertEquals("a\n```\n[[X]]\n```\nb [Y](<wiki:Y>)", NoteLinks.toMarkdownLinks(fenced, "wiki:"))
        assertEquals("sin enlaces\n", NoteLinks.toMarkdownLinks("sin enlaces\n", "wiki:"))
    }

    private val notes = listOf(
        path("Compra.md"),
        path("Casa/Compra.md"),
        path("Casa/Obra.md"),
        path("Viaje/Ideas.txt"),
        path("Casa/.attachments.k3/Obra.png"),
    )

    @Test
    fun `resolves by title, ignoring case and the extension`() {
        assertEquals(path("Viaje/Ideas.txt"), NoteLinks.resolve("ideas", notes))
        assertEquals(path("Casa/Obra.md"), NoteLinks.resolve("Obra.md", notes))
    }

    @Test
    fun `a category picks one, and so does the linking note's category`() {
        assertEquals(path("Casa/Compra.md"), NoteLinks.resolve("casa/compra", notes))
        assertEquals(path("Compra.md"), NoteLinks.resolve("/Compra", notes))
        assertEquals(path("Casa/Compra.md"), NoteLinks.resolve("Compra", notes, from = path("Casa/Obra.md")))
        assertEquals(path("Casa/Compra.md"), NoteLinks.resolve("Compra", notes, from = path("Viaje/Ideas.txt")))
    }

    @Test
    fun `a title with characters a file name drops still resolves`() {
        assertEquals(path("Casa/Obra.md"), NoteLinks.resolve("Obra?", notes))
    }

    @Test
    fun `missing notes and attachments do not resolve`() {
        assertNull(NoteLinks.resolve("Nada", notes))
        assertNull(NoteLinks.resolve("Otra/Obra", notes))
    }

    @Test
    fun `backlinks are the notes that link here, not the note itself`() {
        val texts = mapOf(
            path("Casa/Obra.md") to "Obra\n\n[[Compra]] y [[Obra]]",
            path("Viaje/Ideas.txt") to "[[Casa/Compra]]",
            path("Compra.md") to "nada",
            path("Casa/Compra.md") to "[[Compra]]",
        )
        assertEquals(listOf(path("Casa/Obra.md"), path("Viaje/Ideas.txt")), NoteLinks.backlinks(path("Casa/Compra.md"), texts))
        assertEquals(emptyList<NotePath>(), NoteLinks.backlinks(path("Compra.md"), texts))
    }
}
