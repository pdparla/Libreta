package com.parla.libreta.core.note

import com.parla.libreta.core.testing.FakeLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteServiceTest {

    private val local = FakeLocal()
    private val service = NoteService(local, ConflictNamer { "2026-10-11" })

    private fun saved(result: SaveResult): NoteEntry = (result as SaveResult.Saved).note

    @Test
    fun `new note is named after its first line`() {
        val note = saved(service.save(NoteDraft.blank().edit("# Lista de la compra\n- pan")))

        assertEquals(NotePath("Lista de la compra.md"), note.path)
        assertEquals("# Lista de la compra\n- pan", local.contentOf("Lista de la compra.md"))
    }

    @Test
    fun `new note never overwrites another one with the same title`() {
        local.put("Ideas.md", "# Ideas\nexisting")

        val note = saved(service.save(NoteDraft.blank().edit("# Ideas\nnew")))

        assertEquals(NotePath("Ideas (2).md"), note.path)
        assertEquals("# Ideas\nexisting", local.contentOf("Ideas.md"))
    }

    @Test
    fun `new note without text is not created`() {
        assertEquals(SaveResult.NothingToSave, service.save(NoteDraft.blank().edit("  \n ")))
        assertTrue(local.snapshot().isEmpty())
    }

    @Test
    fun `unchanged draft is not written`() {
        local.put("Ideas.md", "Ideas")
        val draft = service.open(NotePath("Ideas.md"))

        assertEquals(SaveResult.NothingToSave, service.save(draft))
        assertEquals(0, local.writes)
    }

    @Test
    fun `edit keeping the title stays in the same file`() {
        local.put("Recetas/Pan.md", "Pan\nharina")
        val draft = service.open(NotePath("Recetas/Pan.md"))

        val note = saved(service.save(draft.edit("Pan\nharina, agua")))

        assertEquals(NotePath("Recetas/Pan.md"), note.path)
        assertEquals("Pan\nharina, agua", local.contentOf("Recetas/Pan.md"))
    }

    @Test
    fun `title change renames the file within its category`() {
        local.put("Recetas/Pan.md", "Pan\nharina")
        val draft = service.open(NotePath("Recetas/Pan.md"))

        val note = saved(service.save(draft.edit("Pan integral\nharina")))

        assertEquals(NotePath("Recetas/Pan integral.md"), note.path)
        assertEquals(mapOf("Recetas/Pan integral.md" to "Pan integral\nharina"), local.snapshot())
    }

    @Test
    fun `numbered note keeps its name while the title still collides`() {
        local.put("Ideas.md", "Ideas")
        local.put("Ideas (2).md", "Ideas\nsecond")
        val draft = service.open(NotePath("Ideas (2).md"))

        val note = saved(service.save(draft.edit("Ideas\nsecond, edited")))

        assertEquals(NotePath("Ideas (2).md"), note.path)
    }

    @Test
    fun `extension is kept on rename`() {
        local.put("old.txt", "old")
        val note = saved(service.save(service.open(NotePath("old.txt")).edit("new")))

        assertEquals(NotePath("new.txt"), note.path)
    }

    @Test
    fun `emptied note keeps its name`() {
        local.put("Ideas.md", "Ideas")
        val note = saved(service.save(service.open(NotePath("Ideas.md")).edit("")))

        assertEquals(NotePath("Ideas.md"), note.path)
        assertEquals("", local.contentOf("Ideas.md"))
    }

    @Test
    fun `saving over a version changed meanwhile keeps both`() {
        local.put("Ideas.md", "Ideas\nv1")
        val draft = service.open(NotePath("Ideas.md"))
        local.put("Ideas.md", "Ideas\nv2 from sync")

        val result = service.save(draft.edit("Ideas\nv1 edited"))

        val copy = NotePath("Ideas (conflicto 2026-10-11).md")
        assertEquals(SaveResult.SavedAsConflictCopy(NoteEntry(copy, ContentHash.of("Ideas\nv1 edited")), NotePath("Ideas.md")), result)
        assertEquals("Ideas\nv2 from sync", local.contentOf("Ideas.md"))
        assertEquals("Ideas\nv1 edited", local.contentOf(copy.value))
    }

    @Test
    fun `rename over a version changed meanwhile keeps the old file`() {
        local.put("Ideas.md", "Ideas\nv1")
        val draft = service.open(NotePath("Ideas.md"))
        local.put("Ideas.md", "Ideas\nv2 from sync")

        val note = saved(service.save(draft.edit("Ideas nuevas\nv1 edited")))

        assertEquals(NotePath("Ideas nuevas.md"), note.path)
        assertEquals("Ideas\nv2 from sync", local.contentOf("Ideas.md"))
    }

    @Test
    fun `saving a note deleted meanwhile brings it back`() {
        local.put("Recetas/Pan.md", "Pan\nv1")
        val draft = service.open(NotePath("Recetas/Pan.md"))
        local.remove("Recetas/Pan.md")

        val note = saved(service.save(draft.edit("Pan\nv1 edited")))

        assertEquals(NotePath("Recetas/Pan.md"), note.path)
        assertEquals("Pan\nv1 edited", local.contentOf("Recetas/Pan.md"))
    }

    // Categories

    @Test
    fun `new note goes to the chosen category`() {
        val note = saved(service.save(NoteDraft.blank(category = "Recetas").edit("Pan\nharina")))

        assertEquals(NotePath("Recetas/Pan.md"), note.path)
    }

    @Test
    fun `changing the category moves the file even if the text is unchanged`() {
        local.put("Ideas.md", "Ideas")
        val draft = service.open(NotePath("Ideas.md")).moveTo("Trabajo/Proyectos")

        val note = saved(service.save(draft))

        assertEquals(NotePath("Trabajo/Proyectos/Ideas.md"), note.path)
        assertEquals(mapOf("Trabajo/Proyectos/Ideas.md" to "Ideas"), local.snapshot())
    }

    @Test
    fun `moving to no category puts the note at the top level`() {
        local.put("Recetas/Pan.md", "Pan")
        val note = saved(service.save(service.open(NotePath("Recetas/Pan.md")).moveTo("")))

        assertEquals(NotePath("Pan.md"), note.path)
    }

    @Test
    fun `moving into a category with the same title numbers the note`() {
        local.put("Recetas/Pan.md", "Pan\nde la abuela")
        local.put("Pan.md", "Pan\nmío")

        val note = saved(service.save(service.open(NotePath("Pan.md")).moveTo("Recetas")))

        assertEquals(NotePath("Recetas/Pan (2).md"), note.path)
        assertEquals("Pan\nde la abuela", local.contentOf("Recetas/Pan.md"))
    }

    @Test
    fun `category typed by the user is cleaned`() {
        assertEquals("Trabajo/Ideas", NoteDraft.blank().moveTo(" /Trabajo//.Ideas?/ ").category)
    }

    @Test
    fun `saved draft is no longer modified`() {
        local.put("Ideas.md", "Ideas")
        val draft = service.open(NotePath("Ideas.md")).moveTo("Trabajo").edit("Ideas\nmás")

        val note = saved(service.save(draft))

        val next = draft.savedAs(note)
        assertEquals(false, next.isModified)
        assertEquals("Trabajo", next.category)
    }

    // Attachments

    private val withImage = "Pan\n![Página 1](.attachments.k3/pagina-1.png)\n[libreta-ink]: .attachments.k3/libreta.ink.json"

    @Test
    fun `moving to another category takes the attachments along`() {
        local.put("Recetas/Pan.md", withImage)
        local.putBytes("Recetas/.attachments.k3/pagina-1.png", byteArrayOf(1, 2, 3))
        local.put("Recetas/.attachments.k3/libreta.ink.json", "{}")

        saved(service.save(service.open(NotePath("Recetas/Pan.md")).moveTo("Panadería")))

        assertEquals(
            setOf("Panadería/Pan.md", "Panadería/.attachments.k3/pagina-1.png", "Panadería/.attachments.k3/libreta.ink.json"),
            local.snapshot().keys,
        )
    }

    @Test
    fun `renaming within the category leaves the attachments where they are`() {
        local.put("Recetas/Pan.md", withImage)
        local.putBytes("Recetas/.attachments.k3/pagina-1.png", byteArrayOf(1, 2, 3))

        saved(service.save(service.open(NotePath("Recetas/Pan.md")).edit(withImage.replaceFirst("Pan", "Pan casero"))))

        assertEquals(setOf("Recetas/Pan casero.md", "Recetas/.attachments.k3/pagina-1.png"), local.snapshot().keys)
    }

    @Test
    fun `deleting a note deletes its attachments only`() {
        local.put("Pan.md", withImage)
        local.putBytes(".attachments.k3/pagina-1.png", byteArrayOf(1))
        local.putBytes(".attachments.zz/otra.png", byteArrayOf(2))

        service.delete(NotePath("Pan.md"))

        assertEquals(setOf(".attachments.zz/otra.png"), local.snapshot().keys)
    }

    @Test
    fun `delete removes the note and tolerates a missing one`() {
        local.put("Ideas.md", "Ideas")

        service.delete(NotePath("Ideas.md"))
        service.delete(NotePath("Ideas.md"))

        assertNull(local.contentOf("Ideas.md"))
    }
}
