package com.parla.libreta.core.note

import com.parla.libreta.core.ink.InkDocument
import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.ink.InkTranscriptService
import com.parla.libreta.core.testing.FakeLocal
import org.junit.Assert.assertEquals
import org.junit.Test

class NotebookIndexTest {

    private val local = FakeLocal()
    private val noteService = NoteService(local, ConflictNamer { "2026-10-12" })
    private val inkNotes = InkNoteService(local, noteService, { "png".toByteArray() }, { "k3" })
    private val transcripts = InkTranscriptService(local)
    private val index = NotebookIndex(local, inkNotes, transcripts, exists = { local.readOrNull(it) != null })

    @Test
    fun `text and handwritten notes are indexed by what they say, attachments are not notes`() {
        local.put("Casa/Presupuesto.md", "# Presupuesto\n\nPintar el salón")
        val ink = inkNotes.create("", "Compra", InkDocument.blank(600, 900))
        transcripts.save(ink, 0, "h", "pan y leche")

        val entries = index.all().associateBy { it.path.value }

        assertEquals(setOf("Casa/Presupuesto.md", "Compra.md"), entries.keys)
        assertEquals(false, entries.getValue("Casa/Presupuesto.md").isInk)
        assertEquals("# Presupuesto\n\nPintar el salón", entries.getValue("Casa/Presupuesto.md").searchable.text)
        val compra = entries.getValue("Compra.md")
        assertEquals(true, compra.isInk)
        assertEquals("# Compra", compra.searchable.text)
        assertEquals(listOf("pan y leche"), compra.searchable.pages)
        assertEquals(NotePath("Compra.md"), NoteSearch.search(index.all().map { it.searchable }, "leche").single().path)
    }
}
