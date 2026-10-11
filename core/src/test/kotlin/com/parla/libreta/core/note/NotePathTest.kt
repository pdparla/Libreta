package com.parla.libreta.core.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotePathTest {

    @Test
    fun `splits category, base name and extension`() {
        val path = NotePath("Recetas/Postres/Flan.md")
        assertEquals("Recetas/Postres", path.category)
        assertEquals("Flan.md", path.fileName)
        assertEquals("Flan", path.baseName)
        assertEquals("md", path.extension)
    }

    @Test
    fun `top-level note has an empty category`() {
        assertEquals("", NotePath("Ideas.md").category)
        assertEquals(NotePath("Ideas.md"), NotePath.of("", "Ideas"))
    }

    @Test
    fun `base name can contain dots`() {
        val path = NotePath("v1.2 notas.txt")
        assertEquals("v1.2 notas", path.baseName)
        assertEquals(NotePath("v1.3 notas.txt"), path.withBaseName("v1.3 notas"))
    }

    @Test
    fun `accepts md and txt in any case`() {
        assertNotNull(NotePath.parseOrNull("a.md"))
        assertNotNull(NotePath.parseOrNull("a.TXT"))
        assertNotNull(NotePath.parseOrNull("Cat/a.Md"))
    }

    @Test
    fun `rejects anything that is not a note`() {
        listOf(
            "", "a.png", "a", ".md", "a.md/", "/a.md", "a//b.md", "../a.md", "Cat/../a.md",
            "a\\b.md", ".hidden.md", ".attachments.123/foto.md", "Cat/.trash/a.md", "  /a.md", "a\n.md",
        ).forEach { raw -> assertNull("'$raw' should be rejected", NotePath.parseOrNull(raw)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor validates`() {
        NotePath("../escape.md")
    }
}
