package com.parla.libreta.core.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NoteNamingTest {

    @Test
    fun `title is the first non-blank line without Markdown markers`() {
        assertEquals("Lista de la compra", NoteNaming.titleOf("\n\n# Lista de la compra\n- pan"))
        assertEquals("Tarea", NoteNaming.titleOf("- Tarea\nmás"))
        assertEquals("Hola", NoteNaming.titleOf("   Hola   "))
        assertEquals("#hashtag", NoteNaming.titleOf("#hashtag"))
    }

    @Test
    fun `empty content gets the default title`() {
        assertEquals(NoteNaming.DEFAULT_TITLE, NoteNaming.titleOf(""))
        assertEquals(NoteNaming.DEFAULT_TITLE, NoteNaming.titleOf("  \n\t\n"))
        assertEquals(NoteNaming.DEFAULT_TITLE, NoteNaming.titleOf("#\ntexto"))
    }

    @Test
    fun `file name drops characters Nextcloud Notes does not allow`() {
        assertEquals("ab c d", NoteNaming.fileBaseName("a/b: c* d?"))
        assertEquals("Quoted", NoteNaming.fileBaseName("\"Quoted\""))
        assertEquals("oculto", NoteNaming.fileBaseName("...oculto"))
        assertEquals("tab y salto", NoteNaming.fileBaseName("tab\ty\nsalto"))
    }

    @Test
    fun `file name is never empty and never too long`() {
        assertEquals(NoteNaming.DEFAULT_TITLE, NoteNaming.fileBaseName("/// ..."))
        val long = NoteNaming.fileBaseName("x".repeat(500))
        assertEquals(NoteNaming.MAX_TITLE_LENGTH, long.length)
    }

    @Test
    fun `file name is always a valid note path`() {
        listOf("a/b", "..", "\u0000", "Ñandú & café").forEach { title ->
            assertNotEquals(null, NotePath.parseOrNull(NoteNaming.fileBaseName(title) + ".md"))
        }
    }

    @Test
    fun `category keeps sub-categories and drops unsafe or empty levels`() {
        assertEquals("Recetas/Postres", NoteNaming.categoryName("Recetas/Postres"))
        assertEquals("Trabajo/Ideas", NoteNaming.categoryName("  /Trabajo//..Ideas:/ "))
        assertEquals("", NoteNaming.categoryName(" / . / "))
        assertEquals("Mis notas", NoteNaming.categoryName("Mis   notas"))
    }

    @Test
    fun `unique path counts up while the name is taken`() {
        val taken = setOf(NotePath("Cat/Ideas.md"), NotePath("Cat/Ideas (2).md"))
        assertEquals(NotePath("Cat/Ideas (3).md"), NoteNaming.uniquePath("Ideas", "Cat", { it in taken }))
        assertEquals(NotePath("Ideas.md"), NoteNaming.uniquePath("Ideas", "", { it in taken }))
    }
}
