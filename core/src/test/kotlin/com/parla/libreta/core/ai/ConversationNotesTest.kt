package com.parla.libreta.core.ai

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.testing.FakeLocal
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationNotesTest {

    private val local = FakeLocal()
    private val notes = ConversationNotes(local, newFolderId = { "c7" })
    private val messages = listOf(
        ChatMessage(Speaker.USER, "¿Qué pone aquí?"),
        ChatMessage(Speaker.ASSISTANT, "Pone «lista de la compra».\n"),
    )

    @Test
    fun `saves the selection as an attachment and the messages as Markdown`() {
        val path = notes.save("Casa", "Conversación IA 2026-10-11 21.40", byteArrayOf(1, 2), messages)

        assertEquals(NotePath("Casa/Conversación IA 2026-10-11 21.40.md"), path)
        assertEquals(setOf(path.value, "Casa/.attachments.c7/recorte.png"), local.snapshot().keys)
        assertEquals(
            "# Conversación IA 2026-10-11 21.40\n\n![Recorte](.attachments.c7/recorte.png)\n\n" +
                "**Tú:** ¿Qué pone aquí?\n\n**IA:** Pone «lista de la compra».\n",
            local.contentOf(path.value),
        )
    }

    @Test
    fun `a taken title gets a number and no image means no attachment`() {
        local.put("Charla.md", "# Charla\n")

        val path = notes.save("", "Charla", null, messages)

        assertEquals(NotePath("Charla (2).md"), path)
        assertEquals(setOf("Charla.md", "Charla (2).md"), local.snapshot().keys)
    }
}
