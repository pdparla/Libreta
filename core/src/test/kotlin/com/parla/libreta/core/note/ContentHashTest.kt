package com.parla.libreta.core.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHashTest {

    @Test
    fun `is the SHA-1 of the UTF-8 bytes`() =
        assertEquals(Version("sha1:a9993e364706816aba3e25717850c26c9cd0d89d"), ContentHash.of("abc"))

    @Test
    fun `changes with the content`() =
        assertNotEquals(ContentHash.of("niño"), ContentHash.of("nino"))
}
