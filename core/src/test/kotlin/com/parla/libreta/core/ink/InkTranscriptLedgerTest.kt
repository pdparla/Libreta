package com.parla.libreta.core.ink

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTranscriptLedgerTest {

    private val note = NotePath("Compra.md")
    private val strokes = NotePath(".attachments.k3/libreta.ink.json")
    private val text = NotePath(".attachments.k3/libreta.text.json")
    private val markdown = InkNoteFormat.compose("Compra", ".attachments.k3", 1)

    private fun versions(vararg entries: Pair<NotePath, String>) = entries.associate { (path, v) -> path to Version(v) }

    private val settled = versions(note to "n1", strokes to "s1", text to "t1")

    @Test
    fun `dependencies are the note, its strokes and its transcripts`() {
        assertEquals(listOf(note, strokes, text), InkNoteFiles.dependenciesOf(note, markdown))
        assertEquals(listOf(note), InkNoteFiles.dependenciesOf(note, "solo texto"))
    }

    @Test
    fun `a settled note stays settled while its files are unchanged`() {
        val ledger = InkTranscriptLedger()
        assertFalse(ledger.isSettled(note, settled))

        ledger.markSettled(note, markdown, settled)

        assertTrue(ledger.isSettled(note, settled + versions(NotePath("Otra.md") to "x")))
    }

    @Test
    fun `a change in the note, the strokes or the transcripts unsettles it`() {
        val ledger = InkTranscriptLedger()
        ledger.markSettled(note, markdown, settled)

        assertFalse(ledger.isSettled(note, settled + versions(note to "n2")))
        assertFalse(ledger.isSettled(note, settled + versions(strokes to "s2")))
        assertFalse(ledger.isSettled(note, settled + versions(text to "t2")))
        assertFalse(ledger.isSettled(note, settled - text))
    }

    @Test
    fun `a missing file is recorded as missing and unsettles the note when it appears`() {
        val ledger = InkTranscriptLedger()
        val noTranscripts = versions(note to "n1", strokes to "s1")
        ledger.markSettled(note, markdown, noTranscripts)

        assertTrue(ledger.isSettled(note, noTranscripts))
        assertFalse(ledger.isSettled(note, settled))
    }

    @Test
    fun `notes that are gone are forgotten`() {
        val ledger = InkTranscriptLedger()
        ledger.markSettled(note, markdown, settled)

        ledger.retainOnly(emptySet())

        assertFalse(ledger.isSettled(note, settled))
    }
}
