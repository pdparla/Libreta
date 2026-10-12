package com.parla.libreta.core.note

import com.parla.libreta.core.ink.InkDocument
import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.ink.InkTranscriptService
import com.parla.libreta.core.testing.FakeLocal
import org.junit.Assert.assertEquals
import org.junit.Test

/** Counts the reads that reach the store. */
private class CountingRepository(private val inner: NoteRepository) : NoteRepository by inner {
    val reads = mutableListOf<String>()
    override fun read(path: NotePath): VersionedContent {
        reads += path.value
        return inner.read(path)
    }
}

class NotebookIndexCacheTest {

    private val local = FakeLocal()
    private val counting = CountingRepository(local)
    private val noteService = NoteService(local, ConflictNamer { "2026-10-12" })
    private val inkNotes = InkNoteService(local, noteService, { "png".toByteArray() }, { "k3" })
    private val transcripts = InkTranscriptService(counting)
    private val index = NotebookIndex(counting, inkNotes, transcripts, exists = { local.exists(it) })

    private fun entries() = index.entries(counting.list()).associateBy { it.path.value }

    @Test
    fun `a second call with nothing changed reads nothing`() {
        local.put("Casa/Presupuesto.md", "# Presupuesto\n\n#casa pintar")
        val ink = inkNotes.create("", "Compra", InkDocument.blank(600, 900))
        transcripts.save(ink, 0, "h", "pan y leche")

        val first = entries()
        assertEquals(setOf("Casa/Presupuesto.md", "Compra.md"), first.keys)
        assertEquals(listOf("casa"), first.getValue("Casa/Presupuesto.md").tags)
        counting.reads.clear()

        val second = entries()

        assertEquals(emptyList<String>(), counting.reads)
        assertEquals(first, second)
    }

    @Test
    fun `only the note that changed is read again`() {
        local.put("A.md", "uno")
        local.put("B.md", "dos")
        entries()
        counting.reads.clear()

        local.put("B.md", "dos y tres")
        val entries = entries()

        assertEquals(listOf("B.md"), counting.reads)
        assertEquals("dos y tres", entries.getValue("B.md").searchable.text)
        assertEquals("uno", entries.getValue("A.md").searchable.text)
    }

    @Test
    fun `a new transcript of a handwritten note is picked up and nothing else is read`() {
        val ink = inkNotes.create("", "Compra", InkDocument.blank(600, 900))
        local.put("A.md", "uno")
        transcripts.save(ink, 0, "h", "pan")
        assertEquals(listOf("pan"), entries().getValue("Compra.md").searchable.pages)

        transcripts.save(ink, 0, "h2", "pan y leche")
        counting.reads.clear()
        val entries = entries()

        assertEquals(listOf("Compra.md", ".attachments.k3/libreta.text.json"), counting.reads)
        assertEquals(listOf("pan y leche"), entries.getValue("Compra.md").searchable.pages)
    }

    @Test
    fun `a strokes file appearing turns a text note into a handwritten one`() {
        inkNotes.create("", "Compra", InkDocument.blank(600, 900))
        val strokes = local.contentOf(".attachments.k3/libreta.ink.json")!!
        local.remove(".attachments.k3/libreta.ink.json")
        assertEquals(false, entries().getValue("Compra.md").isInk)
        counting.reads.clear()

        local.put(".attachments.k3/libreta.ink.json", strokes)
        val entries = entries()

        // The note and its (absent) transcripts: nothing else.
        assertEquals(listOf("Compra.md", ".attachments.k3/libreta.text.json"), counting.reads)
        assertEquals(true, entries.getValue("Compra.md").isInk)
    }

    @Test
    fun `deleted notes are dropped and the index follows the listing`() {
        local.put("A.md", "uno")
        local.put("B.md", "dos")
        entries()

        local.remove("A.md")

        assertEquals(setOf("B.md"), entries().keys)
    }
}
