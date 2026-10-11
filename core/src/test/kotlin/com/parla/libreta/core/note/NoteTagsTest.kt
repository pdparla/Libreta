package com.parla.libreta.core.note

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTagsTest {

    @Test
    fun `tags are words after a hash, not headings, links or anchors`() {
        val markdown = "# Viaje\n\n## Índice\n\nVer [Compra](<nota:Casa/Compra.md#2>) y C#.\n\nEtiquetas: #viajes #Lisboa-2026 #viajes\n\nTexto con #idea suelta."

        assertEquals(listOf("viajes", "lisboa-2026", "idea"), NoteTags.of(markdown))
    }
}
