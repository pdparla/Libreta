package com.parla.libreta.data.local

import com.parla.libreta.core.note.ContentHash
import com.parla.libreta.core.note.NoteEntry
import com.parla.libreta.core.note.NoteNotFoundException
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.PreconditionFailedException
import com.parla.libreta.core.note.writeText
import com.parla.libreta.core.note.WritePrecondition.MustMatch
import com.parla.libreta.core.note.WritePrecondition.MustNotExist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileNoteRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: File by lazy { File(temp.root, "notes") }
    private val repository by lazy { FileNoteRepository(root) }

    private fun file(relative: String) = File(root, relative)

    @Test
    fun `empty or missing root has no notes`() {
        assertEquals(emptyList<NoteEntry>(), repository.list())
        assertEquals(emptyList<NoteSummary>(), repository.summaries())
    }

    @Test
    fun `write creates category folders and returns the content hash`() {
        val version = repository.writeText(NotePath("Recetas/Postres/Flan.md"), "Flan\nhuevos", MustNotExist)

        assertEquals(ContentHash.of("Flan\nhuevos"), version)
        assertEquals("Flan\nhuevos", file("Recetas/Postres/Flan.md").readText())
    }

    @Test
    fun `round-trips UTF-8 text and file names`() {
        repository.writeText(NotePath("Cocina/Ñoquis con café.md"), "¿Qué tal? 🍝")

        val note = repository.read(NotePath("Cocina/Ñoquis con café.md"))

        assertEquals("¿Qué tal? 🍝", note.text)
        assertEquals(ContentHash.of("¿Qué tal? 🍝"), note.version)
    }

    @Test
    fun `lists only notes, skipping hidden entries and other files`() {
        repository.writeText(NotePath("a.md"), "a")
        repository.writeText(NotePath("Cat/b.txt"), "b")
        file(".attachments.12/foto.md").apply { parentFile?.mkdirs(); writeText("x") }
        file("Cat/.a.md.tmp").writeText("leftover")
        file("imagen.png").writeText("png")

        assertEquals(
            listOf(NoteEntry(NotePath("Cat/b.txt"), ContentHash.of("b")), NoteEntry(NotePath("a.md"), ContentHash.of("a"))),
            repository.list().sortedBy { it.path },
        )
        assertEquals(setOf(NotePath("a.md"), NotePath("Cat/b.txt")), repository.summaries().map { it.path }.toSet())
    }

    @Test
    fun `attachments are listed and round-trip as bytes, but are not notes`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, -1)
        repository.writeText(NotePath("Dibujos/Boceto.md"), "Boceto\n![](.attachments.k3/pagina-1.png)")
        repository.write(NotePath("Dibujos/.attachments.k3/pagina-1.png"), png, MustNotExist)
        file("Dibujos/.otra/x.png").apply { parentFile?.mkdirs(); writeText("not synced") }

        assertEquals(
            listOf(NotePath("Dibujos/.attachments.k3/pagina-1.png"), NotePath("Dibujos/Boceto.md")),
            repository.list().map { it.path }.sorted(),
        )
        assertTrue(png.contentEquals(repository.read(NotePath("Dibujos/.attachments.k3/pagina-1.png")).bytes))
        assertEquals(listOf(NotePath("Dibujos/Boceto.md")), repository.summaries().map { it.path })
    }

    @Test
    fun `cached versions follow every write`() {
        val path = NotePath(".attachments.k3/libreta.ink.json")
        repository.writeText(path, "{\"v\":1}")
        val first = repository.list().single().version

        repository.writeText(path, "{\"v\":2}")

        val second = repository.list().single().version
        assertEquals(ContentHash.of("{\"v\":2}"), second)
        assertTrue(first != second)
    }

    @Test
    fun `summaries carry the modification time`() {
        repository.writeText(NotePath("a.md"), "a")
        file("a.md").setLastModified(1_700_000_000_000)

        assertEquals(1_700_000_000_000, repository.summaries().single().modifiedAt)
    }

    @Test(expected = NoteNotFoundException::class)
    fun `reading a missing note fails`() {
        repository.read(NotePath("nope.md"))
    }

    @Test
    fun `MustMatch protects against overwriting a changed note`() {
        val v1 = repository.writeText(NotePath("a.md"), "v1")
        file("a.md").writeText("changed behind our back")

        assertThrows<PreconditionFailedException> { repository.writeText(NotePath("a.md"), "v2", MustMatch(v1)) }
        assertThrows<PreconditionFailedException> { repository.delete(NotePath("a.md"), MustMatch(v1)) }
        assertEquals("changed behind our back", file("a.md").readText())
    }

    @Test
    fun `MustNotExist refuses to overwrite`() {
        repository.writeText(NotePath("a.md"), "v1")

        assertThrows<PreconditionFailedException> { repository.writeText(NotePath("a.md"), "v2", MustNotExist) }
    }

    @Test
    fun `write leaves no temporary files behind`() {
        repository.writeText(NotePath("Cat/a.md"), "v1")
        repository.writeText(NotePath("Cat/a.md"), "v2")

        assertEquals(listOf("a.md"), file("Cat").list()!!.toList())
    }

    @Test
    fun `deleting the last note of a category removes its folders`() {
        repository.writeText(NotePath("Recetas/Postres/Flan.md"), "flan")
        repository.writeText(NotePath("Otra.md"), "otra")

        repository.delete(NotePath("Recetas/Postres/Flan.md"))

        assertFalse(file("Recetas").exists())
        assertTrue(root.isDirectory)
    }

    @Test
    fun `deleting keeps folders that still have notes`() {
        repository.writeText(NotePath("Recetas/Pan.md"), "pan")
        repository.writeText(NotePath("Recetas/Flan.md"), "flan")

        repository.delete(NotePath("Recetas/Pan.md"))

        assertTrue(file("Recetas/Flan.md").isFile)
    }

    @Test
    fun `deleting the only top-level note keeps the root`() {
        repository.writeText(NotePath("a.md"), "a")

        repository.delete(NotePath("a.md"))

        assertTrue(root.isDirectory)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
