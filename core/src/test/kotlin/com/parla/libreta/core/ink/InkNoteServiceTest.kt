package com.parla.libreta.core.ink

import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteService
import com.parla.libreta.core.testing.FakeLocal
import com.parla.libreta.core.note.readOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkNoteServiceTest {

    private val local = FakeLocal()
    private val noteService = NoteService(local, ConflictNamer { "2026-10-11" })
    private val rendered = mutableListOf<InkPage>()
    private val service = InkNoteService(
        notes = local,
        noteService = noteService,
        renderer = { page -> rendered += page; "png:${page.strokes.size}".toByteArray() },
        newFolderId = { "k3" },
    )

    private fun files() = local.snapshot().keys

    @Test
    fun `create writes strokes, page images and the Markdown`() {
        val note = service.create("Dibujos", "Boceto", InkDocument.blank(600, 900))

        assertEquals(NotePath("Dibujos/Boceto.md"), note.path)
        assertEquals(
            setOf("Dibujos/Boceto.md", "Dibujos/.attachments.k3/libreta.ink.json", "Dibujos/.attachments.k3/pagina-1.png"),
            files(),
        )
        assertTrue(local.contentOf("Dibujos/Boceto.md")!!.startsWith("# Boceto\n"))
    }

    @Test
    fun `open reads the strokes back`() {
        val created = service.create("", "Boceto", InkDocument(listOf(InkPage(600, 900, Paper.LINED, listOf(stroke(1f, 2f, 3f, 4f))))))

        val opened = service.open(created.path)

        assertEquals(created.document.pages[0].strokes[0].points.size, opened.document.pages[0].strokes[0].points.size)
        assertEquals(".attachments.k3", opened.folder)
    }

    @Test
    fun `a note whose reference line Nextcloud Notes dropped is still handwritten, and saving restores the line`() {
        val created = service.create("", "Boceto", InkDocument.blank(600, 900))
        val stripped = local.contentOf("Boceto.md")!!.replace(Regex("""\n\[libreta-ink]: .*\n"""), "\n")
        local.put("Boceto.md", stripped)
        val plain = NotePath("Normal.md").also { local.put(it.value, "# Normal\n\n![Foto](.attachments.p1/foto.jpg)\n") }
        local.putBytes(".attachments.p1/foto.jpg", byteArrayOf(1))
        val exists = { path: NotePath -> local.readOrNull(path) != null }

        assertFalse(stripped.contains("libreta-ink"))
        assertTrue(service.isInkNote(created.path, stripped, exists))
        assertFalse(service.isInkNote(plain, local.contentOf(plain.value)!!, exists))
        val opened = service.open(created.path)
        assertEquals(".attachments.k3", opened.folder)

        service.save(opened, InkEditor(opened.document).apply { addStroke(stroke(1f, 1f)) }.document)
        assertTrue(local.contentOf("Boceto.md")!!.contains("[libreta-ink]: .attachments.k3/libreta.ink.json"))
    }

    @Test
    fun `save renders only changed pages and removes pages that are gone`() {
        val note = service.create("", "Boceto", InkDocument.blank(600, 900))
        val twoPages = InkEditor(note.document).apply { addPage(); addStroke(stroke(1f, 1f)) }.document
        val saved = service.save(note, twoPages)
        rendered.clear()

        val onePage = InkDocument(listOf(twoPages.pages[0]))
        service.save(saved, onePage)

        assertEquals(emptyList<InkPage>(), rendered) // page 1 did not change
        assertEquals(setOf("Boceto.md", ".attachments.k3/libreta.ink.json", ".attachments.k3/pagina-1.png"), files())
        assertEquals(false, local.contentOf("Boceto.md")!!.contains("pagina-2"))
    }

    @Test
    fun `save merges into text added to the Markdown meanwhile`() {
        val note = service.create("", "Lista", InkDocument.blank(600, 900))
        val withText = local.contentOf("Lista.md")!!.replaceFirst("# Lista\n", "# Lista\n\nTexto reconocido\n")
        local.put("Lista.md", withText)

        service.save(note, InkEditor(note.document).apply { addPage() }.document)

        val markdown = local.contentOf("Lista.md")!!
        assertTrue(markdown, markdown.startsWith("# Lista\n\nTexto reconocido\n"))
        assertTrue(markdown.contains("pagina-2.png"))
    }

    @Test
    fun `rename keeps the attachments, move takes them along, delete removes them`() {
        val note = service.create("", "Boceto", InkDocument.blank(600, 900))

        val renamed = service.rename(note, "Plano de la cocina")
        assertEquals(NotePath("Plano de la cocina.md"), renamed.path)
        assertTrue(".attachments.k3/pagina-1.png" in files())
        assertEquals(1, service.open(renamed.path).document.pages.size)

        val moved = service.move(renamed, "Casa")
        assertEquals(
            setOf("Casa/Plano de la cocina.md", "Casa/.attachments.k3/libreta.ink.json", "Casa/.attachments.k3/pagina-1.png"),
            files(),
        )

        noteService.delete(moved.path)
        assertEquals(emptySet<String>(), files())
    }
    @Test
    fun `addText puts recognised text after the user's text and above the pages`() {
        val note = service.create("", "Compra", InkDocument.blank(600, 900))
        local.put("Compra.md", local.contentOf("Compra.md")!!.replace("# Compra\n", "# Compra\n\nEscrito a máquina\n"))

        service.addText(note, "- leche\n- pan\n")
        service.addText(note, "  Llamar a Ana  ")

        assertEquals(
            "# Compra\n\nEscrito a máquina\n\n- leche\n- pan\n\nLlamar a Ana\n\n" +
                "![Página 1](.attachments.k3/pagina-1.png)\n\n[libreta-ink]: .attachments.k3/libreta.ink.json\n",
            local.contentOf("Compra.md"),
        )
    }

    @Test
    fun `blank recognised text changes nothing`() {
        val note = service.create("", "Compra", InkDocument.blank(600, 900))
        val before = local.contentOf("Compra.md")

        service.addText(note, " \n ")

        assertEquals(before, local.contentOf("Compra.md"))
    }
}
