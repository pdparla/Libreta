package com.parla.libreta.core.note

import com.parla.libreta.core.testing.FakeLocal
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteAttachmentsTest {

    @Test
    fun `finds image links and reference definitions`() {
        val note = """
            # Nota a mano

            ![Página 1](.attachments.k3f9/pagina-1.png)
            Pegada en Notes: ![](.attachments.1234/foto.jpg)

            [libreta-ink]: .attachments.k3f9/libreta.ink.json
        """.trimIndent()

        assertEquals(setOf(".attachments.k3f9", ".attachments.1234"), NoteAttachments.foldersReferencedBy(note))
    }

    @Test
    fun `ignores text that only looks like a link`() =
        assertEquals(emptySet<String>(), NoteAttachments.foldersReferencedBy("archivo.attachments.x/ y .attachments./a"))

    @Test
    fun `lists only the files of the linked folders next to the note`() {
        val local = FakeLocal()
        local.put("Recetas/.attachments.a/p.png", "png")
        local.put("Recetas/.attachments.a/libreta.ink.json", "{}")
        local.put("Recetas/.attachments.b/otra.png", "other note")
        local.put(".attachments.a/p.png", "same id, other folder")

        val files = NoteAttachments.filesOf(local, NotePath("Recetas/Pan.md"), "![](.attachments.a/p.png)")

        assertEquals(
            listOf("Recetas/.attachments.a/libreta.ink.json", "Recetas/.attachments.a/p.png"),
            files.map { it.path.value }.sorted(),
        )
    }

    @Test
    fun `relocates between categories`() {
        assertEquals(
            NotePath("Trabajo/.attachments.a/p.png"),
            NoteAttachments.relocated(NotePath("Recetas/.attachments.a/p.png"), NotePath("Recetas/Pan.md"), NotePath("Trabajo/Pan.md")),
        )
        assertEquals(
            NotePath("Trabajo/.attachments.a/p.png"),
            NoteAttachments.relocated(NotePath(".attachments.a/p.png"), NotePath("Pan.md"), NotePath("Trabajo/Pan.md")),
        )
    }
}
