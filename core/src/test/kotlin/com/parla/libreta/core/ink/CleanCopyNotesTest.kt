package com.parla.libreta.core.ink

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.testing.FakeLocal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanCopyNotesTest {

    private val local = FakeLocal()
    private val notes = CleanCopyNotes(local, newFolderId = { "d4" })
    private val footer = "_Pasada a limpio de la nota a mano «Física» el 2026-10-16 18.30._"

    @Test
    fun `writes the drawings as attachments and links them where the model placed them`() {
        val body = "## Circuito\n\n![Esquema [serie]](dibujo:1,10,10,90,90)\n\nLa corriente…\n\n![](dibujo:2,0,0,5,5)"

        val path = notes.save("Clase", "Física (a limpio)", body, footer, listOf(byteArrayOf(1), byteArrayOf(2)))

        assertEquals(NotePath("Clase/Física (a limpio).md"), path)
        assertEquals(
            "# Física (a limpio)\n\n## Circuito\n\n![Esquema (serie)](.attachments.d4/dibujo-1.png)\n\nLa corriente…\n\n" +
                "![Dibujo 2](.attachments.d4/dibujo-2.png)\n\n$footer\n",
            local.contentOf(path.value),
        )
        assertArrayEquals(byteArrayOf(2), local.read(NotePath("Clase/.attachments.d4/dibujo-2.png")).bytes)
    }

    @Test
    fun `a drawing without a picture is left out, and the pictures keep consecutive names`() {
        val body = "![A](dibujo:1,0,0,1,1)\n\nTexto\n\n![B](dibujo:1,0,0,1,1)"

        val path = notes.save("", "Nota (a limpio)", body, footer, listOf(null, byteArrayOf(7)))

        assertEquals("# Nota (a limpio)\n\nTexto\n\n![B](.attachments.d4/dibujo-1.png)\n\n$footer\n", local.contentOf(path.value))
        assertEquals(setOf(path.value, ".attachments.d4/dibujo-1.png"), local.snapshot().keys)
    }

    @Test
    fun `without drawings there are no attachments, and a taken title gets a number`() {
        local.put("Nota (a limpio).md", "# Nota (a limpio)\n")

        val path = notes.save("", "Nota (a limpio)", "Solo texto", footer, emptyList())

        assertEquals(NotePath("Nota (a limpio) (2).md"), path)
        assertEquals(setOf("Nota (a limpio).md", path.value), local.snapshot().keys)
    }
}
