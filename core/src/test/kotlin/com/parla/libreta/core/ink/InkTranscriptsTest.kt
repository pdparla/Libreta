package com.parla.libreta.core.ink

import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NoteService
import com.parla.libreta.core.testing.FakeLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTranscriptsTest {

    private val local = FakeLocal()
    private val inkNotes = InkNoteService(local, NoteService(local, ConflictNamer { "2026-10-12" }), { "png".toByteArray() }, { "k3" })
    private val transcripts = InkTranscriptService(local)

    private fun twoPages() = InkDocument(
        listOf(InkPage(600, 900, Paper.LINED, listOf(stroke(10f, 10f, 50f, 10f))), InkPage(600, 900, Paper.LINED)),
    )

    @Test
    fun `the file round-trips, quotes, line breaks and accents included`() {
        val pages = listOf(PageTranscript("sha1:ab", "Lista \"de\" la compra\n- café\\té"), PageTranscript("sha1:cd", ""))

        assertEquals(pages, InkTranscripts.decode(InkTranscripts.encode(pages)))
    }

    @Test
    fun `every page is pending at first, and only a changed page afterwards`() {
        val note = inkNotes.create("", "Compra", twoPages())
        assertEquals(listOf(0, 1), transcripts.pending(note))

        transcripts.save(note, 0, InkTranscripts.hashOf(note.document.pages[0]), "pan")
        transcripts.save(note, 1, InkTranscripts.hashOf(note.document.pages[1]), "")
        assertEquals(emptyList<Int>(), transcripts.pending(note))
        assertEquals(listOf("pan", ""), transcripts.read(note).map { it.text })

        val editor = InkEditor(note.document)
        editor.goToPage(1)
        editor.addStroke(stroke(5f, 5f, 9f, 9f))
        val changed = inkNotes.save(note, editor.document)
        assertEquals(listOf(1), transcripts.pending(changed))
    }

    @Test
    fun `the transcript lives in the attachments folder, so it moves with the note`() {
        val note = inkNotes.create("", "Compra", twoPages())
        transcripts.save(note, 0, "h", "pan")
        assertTrue(local.snapshot().containsKey(".attachments.k3/libreta.text.json"))

        val moved = inkNotes.move(note, "Casa")

        assertEquals(listOf("pan", ""), transcripts.read(moved).map { it.text })
    }

    @Test
    fun `a note that lost pages keeps only theirs, and an unreadable file counts as none`() {
        val note = inkNotes.create("", "Compra", twoPages())
        transcripts.save(note, 1, "h1", "segunda")
        val onePage = inkNotes.save(note, InkDocument(listOf(note.document.pages[0])))

        transcripts.save(onePage, 0, "h0", "primera")
        assertEquals(listOf("primera"), transcripts.read(onePage).map { it.text })

        local.put(".attachments.k3/libreta.text.json", "not json")
        assertEquals(emptyList<PageTranscript>(), transcripts.read(onePage))
    }

    @Test
    fun `the search index finds the transcript from the Markdown, and the user's text without managed lines`() {
        val note = inkNotes.create("", "Compra", twoPages())
        transcripts.save(note, 0, "h", "pan y leche")
        inkNotes.addText(note, "Para el sábado")
        val markdown = local.contentOf("Compra.md")!!

        assertEquals(listOf("pan y leche", ""), transcripts.readFor(note.path, markdown).map { it.text })
        assertEquals("# Compra\n\nPara el sábado", InkNoteFormat.userText(markdown))
    }
}
