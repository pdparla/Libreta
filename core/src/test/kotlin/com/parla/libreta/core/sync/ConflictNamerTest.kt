package com.parla.libreta.core.sync

import com.parla.libreta.core.note.NotePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictNamerTest {

    private val namer = ConflictNamer { "2026-10-11" }

    @Test
    fun `adds the date to the file name`() =
        assertEquals(NotePath("Ideas (conflicto 2026-10-11).md"), namer.conflictPathFor(NotePath("Ideas.md")) { false })

    @Test
    fun `keeps category and extension`() =
        assertEquals(
            NotePath("Recetas/Pan (conflicto 2026-10-11).txt"),
            namer.conflictPathFor(NotePath("Recetas/Pan.txt")) { false },
        )

    @Test
    fun `counts up while the name is taken`() {
        val taken = setOf(NotePath("Ideas (conflicto 2026-10-11).md"), NotePath("Ideas (conflicto 2026-10-11) 2.md"))
        assertEquals(NotePath("Ideas (conflicto 2026-10-11) 3.md"), namer.conflictPathFor(NotePath("Ideas.md")) { it in taken })
    }

    @Test
    fun `system date is ISO formatted`() =
        assertTrue(SystemToday.isoDate().matches(Regex("""\d{4}-\d{2}-\d{2}""")))
}
