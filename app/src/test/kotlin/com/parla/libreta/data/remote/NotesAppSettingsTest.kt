package com.parla.libreta.data.remote

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotesAppSettingsTest {

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun settings() = NotesAppSettings(OkHttpClient(), server.url("/"))

    @Test
    fun `reads the folder the Notes app uses`() {
        server.enqueue(
            MockResponse().setBody("""{"notesPath":"Notas","fileSuffix":".md","noteMode":"rich","showHidden":false}"""),
        )

        assertEquals("Notas", settings().notesPath())
        val request = server.takeRequest()
        assertEquals("/index.php/apps/notes/api/v1/settings", request.path)
        assertEquals("true", request.getHeader("OCS-APIRequest"))
    }

    @Test
    fun `nested folder with escaped slashes`() =
        assertEquals("Documentos/Mis notas", NotesAppSettings.parseNotesPath("""{"notesPath" : "\/Documentos\/Mis notas\/"}"""))

    @Test
    fun `no Notes app means no folder`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(settings().notesPath())
    }

    @Test
    fun `unreachable server means no folder`() {
        server.shutdown()

        assertNull(settings().notesPath())
    }

    @Test
    fun `missing or empty value means no folder`() {
        assertNull(NotesAppSettings.parseNotesPath("""{"fileSuffix":".md"}"""))
        assertNull(NotesAppSettings.parseNotesPath("""{"notesPath":""}"""))
    }
}
