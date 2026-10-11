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

    @Test
    fun `attachments live directly inside an attachments folder`() {
        assertEquals(NotePath.Kind.ATTACHMENT, NotePath("Recetas/.attachments.k3f9/pagina-1.png").kind)
        assertEquals(NotePath.Kind.ATTACHMENT, NotePath(".attachments.12/libreta.ink.json").kind)
        assertEquals(NotePath.Kind.NOTE, NotePath("Recetas/Pan.md").kind)
        listOf(
            ".attachments./a.png", ".attachments.1/sub/a.png", ".attachments.1/nota.md", "Cat/.oculta/a.png",
            ".attachments.1/.a.png", ".attachments.1/a.exe", ".attachments.1/.attachments.2/a.png", "a.png",
        ).forEach { raw -> assertNull("'$raw' should be rejected", NotePath.parseOrNull(raw)) }
    }

    @Test
    fun `synced folders are categories and attachment folders`() {
        assertEquals(true, NotePath.isSyncedFolder(""))
        assertEquals(true, NotePath.isSyncedFolder("Recetas/Postres"))
        assertEquals(true, NotePath.isSyncedFolder("Recetas/.attachments.12"))
        assertEquals(false, NotePath.isSyncedFolder(".oculta"))
        assertEquals(false, NotePath.isSyncedFolder(".attachments.12/sub"))
        assertEquals(false, NotePath.isSyncedFolder(".attachments."))
    }

    @Test
    fun `links resolve against the note's folder`() {
        assertEquals(
            NotePath("Recetas/.attachments.k3/p.png"),
            NotePath("Recetas/Pan.md").resolveSibling(".attachments.k3/p.png"),
        )
        assertEquals(NotePath(".attachments.k3/p.png"), NotePath("Pan.md").resolveSibling(".attachments.k3/p.png"))
        assertNull(NotePath("Pan.md").resolveSibling("../fuera.png"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor validates`() {
        NotePath("../escape.md")
    }
}
