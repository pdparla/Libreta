package com.parla.libreta.core.note

import com.parla.libreta.core.testing.FakeLocal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRepositoryTest {

    @Test
    fun `default exists is true only for files in the store`() {
        val repository = FakeLocal()
        repository.put("Cocina/Flan.md", "huevos")

        assertTrue(repository.exists(NotePath("Cocina/Flan.md")))
        assertFalse(repository.exists(NotePath("Cocina/Otra.md")))
    }
}
