package com.parla.libreta.data.web

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class YouTubeTest {

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `video ids from the usual addresses`() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?feature=share&v=$id&t=42",
            "http://m.youtube.com/watch?v=$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id",
            "https://www.youtube-nocookie.com/embed/$id",
            "https://music.youtube.com/watch?v=$id&list=RD",
        ).forEach { assertEquals(it, id, YouTubeLinks.videoId(it)) }
    }

    @Test
    fun `not videos`() {
        listOf(
            "https://www.youtube.com/@canal",
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/watch?v=corto",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://example.com/youtu.be/dQw4w9WgXcQ",
            "nota:Casa/Compra.md",
            "no es una dirección",
        ).forEach { assertNull(it, YouTubeLinks.videoId(it)) }
    }

    private fun client() = YouTubeClient(OkHttpClient(), server.url("/"), server.url("/img/"))

    @Test
    fun `title and channel from oEmbed`() {
        server.enqueue(MockResponse().setBody("""{"title":"Cómo funciona","author_name":"Canal","thumbnail_url":"x"}"""))

        val info = client().info("dQw4w9WgXcQ")

        assertEquals("Cómo funciona", info.title)
        assertEquals("Canal", info.channel)
        val request = server.takeRequest()
        assertEquals("/oembed", request.requestUrl.encodedPath())
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", request.requestUrl.queryParameter("url"))
        assertEquals("json", request.requestUrl.queryParameter("format"))
    }

    @Test
    fun `a private or removed video has no title, a server error is an error`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))

        assertNull(client().info("dQw4w9WgXcQ").title)
        try {
            client().info("dQw4w9WgXcQ")
            throw AssertionError("Expected an IOException")
        } catch (e: IOException) {
        }
    }

    @Test
    fun `the small thumbnail`() {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))

        assertArrayEquals(byteArrayOf(1, 2, 3), client().thumbnail("dQw4w9WgXcQ"))
        assertEquals("/img/vi/dQw4w9WgXcQ/mqdefault.jpg", server.takeRequest().path)
    }
}
