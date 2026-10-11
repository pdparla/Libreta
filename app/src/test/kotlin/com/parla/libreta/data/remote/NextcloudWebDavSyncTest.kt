package com.parla.libreta.data.remote

import com.parla.libreta.core.note.NoteEntry
import com.parla.libreta.core.note.NoteNotFoundException
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.PreconditionFailedException
import com.parla.libreta.core.note.StoreUnavailableException
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.note.WritePrecondition
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class NextcloudWebDavSyncTest {

    private val server = MockWebServer()
    private val nextcloud = FakeNextcloud()
    private lateinit var remote: NextcloudWebDavSync

    @Before
    fun setUp() {
        nextcloud.start(server)
        server.start()
        remote = remoteWithPassword("app-password")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun remoteWithPassword(password: String): NextcloudWebDavSync {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic("alice", password)).build())
            }
            .build()
        return NextcloudWebDavSync(client, server.url(nextcloud.basePath))
    }

    private var consumedRequests = 0

    /** Requests received since the last call. MockWebServer's requestCount is cumulative, so track what was taken. */
    private fun requests(): List<RecordedRequest> {
        val fresh = List(server.requestCount - consumedRequests) {
            server.takeRequest(1, TimeUnit.SECONDS) ?: error("request not recorded")
        }
        consumedRequests = server.requestCount
        return fresh
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError()
    }

    // Listing

    @Test
    fun `lists notes in every category with their ETags`() {
        nextcloud.put("Ideas.md", "ideas")
        nextcloud.put("Recetas/Pan integral.md", "pan")
        nextcloud.put("Recetas/Postres/Flan con café.txt", "flan")

        val notes = remote.list().sortedBy { it.path }

        assertEquals(
            listOf(
                NoteEntry(NotePath("Ideas.md"), Version(nextcloud.etagOf("Ideas.md")!!)),
                NoteEntry(NotePath("Recetas/Pan integral.md"), Version(nextcloud.etagOf("Recetas/Pan integral.md")!!)),
                NoteEntry(NotePath("Recetas/Postres/Flan con café.txt"), Version(nextcloud.etagOf("Recetas/Postres/Flan con café.txt")!!)),
            ),
            notes,
        )
    }

    @Test
    fun `listing walks folders one level at a time`() {
        nextcloud.put("Recetas/Postres/Flan.md", "flan")

        remote.list()

        val propfinds = requests().filter { it.method == "PROPFIND" }
        assertEquals(listOf("1", "1", "1"), propfinds.map { it.getHeader("Depth") })
        assertTrue(propfinds.all { it.path.endsWith("/") })
    }

    @Test
    fun `listing skips hidden folders and files that are not notes`() {
        nextcloud.put("Ideas.md", "ideas")
        nextcloud.put(".attachments.42/foto.md", "x")
        nextcloud.put("imagen.png", "png")
        nextcloud.put(".oculta.md", "x")

        assertEquals(listOf(NotePath("Ideas.md")), remote.list().map { it.path })
    }

    @Test
    fun `a missing notes folder is an error, never an empty list`() {
        nextcloud.notesFolderExists = false

        val error = assertThrows<StoreUnavailableException> { remote.list() }

        assertEquals(404, (error.cause as HttpStatusException).code)
    }

    @Test
    fun `wrong password is reported with its HTTP status`() {
        val error = assertThrows<StoreUnavailableException> { remoteWithPassword("wrong").list() }

        assertEquals(401, (error.cause as HttpStatusException).code)
    }

    @Test
    fun `unreachable server is reported as unavailable`() {
        server.shutdown()

        assertThrows<StoreUnavailableException> { remote.list() }
    }

    @Test
    fun `check connection succeeds against an existing notes folder`() {
        remote.checkConnection()

        assertEquals("0", server.takeRequest().getHeader("Depth"))
    }

    // Reading

    @Test
    fun `reads content and ETag`() {
        nextcloud.put("Recetas/Pan integral.md", "harina\nagua")

        val note = remote.read(NotePath("Recetas/Pan integral.md"))

        assertEquals("harina\nagua", note.content)
        assertEquals(Version(nextcloud.etagOf("Recetas/Pan integral.md")!!), note.version)
        assertEquals(nextcloud.basePath + "Recetas/Pan%20integral.md", server.takeRequest().path)
    }

    @Test
    fun `reading a missing note fails with not found`() {
        assertThrows<NoteNotFoundException> { remote.read(NotePath("nope.md")) }
    }

    // Writing

    @Test
    fun `creating sends If-None-Match and returns the new ETag`() {
        val version = remote.write(NotePath("Ideas.md"), "ideas ñ", WritePrecondition.MustNotExist)

        assertEquals("ideas ñ", nextcloud.contentOf("Ideas.md"))
        assertEquals(Version(nextcloud.etagOf("Ideas.md")!!), version)
        val put = server.takeRequest()
        assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals("text/markdown; charset=utf-8", put.getHeader("Content-Type"))
    }

    @Test
    fun `creating over an existing note is rejected by the server`() {
        nextcloud.put("Ideas.md", "theirs")

        assertThrows<PreconditionFailedException> { remote.write(NotePath("Ideas.md"), "mine", WritePrecondition.MustNotExist) }
        assertEquals("theirs", nextcloud.contentOf("Ideas.md"))
    }

    @Test
    fun `updating sends If-Match with the known ETag`() {
        nextcloud.put("Ideas.md", "v1")
        val etag = nextcloud.etagOf("Ideas.md")!!

        remote.write(NotePath("Ideas.md"), "v2", WritePrecondition.MustMatch(Version(etag)))

        assertEquals("v2", nextcloud.contentOf("Ideas.md"))
        assertEquals(etag, server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun `updating a note changed by someone else is rejected`() {
        nextcloud.put("Ideas.md", "v1")
        val stale = nextcloud.etagOf("Ideas.md")!!
        nextcloud.put("Ideas.md", "v2 from the phone")

        assertThrows<PreconditionFailedException> {
            remote.write(NotePath("Ideas.md"), "v2 from the tablet", WritePrecondition.MustMatch(Version(stale)))
        }
        assertEquals("v2 from the phone", nextcloud.contentOf("Ideas.md"))
    }

    @Test
    fun `writing into a new category creates every missing folder level`() {
        remote.write(NotePath("Trabajo/Proyectos/Plan.md"), "plan", WritePrecondition.MustNotExist)

        assertTrue(nextcloud.hasFolder("Trabajo") && nextcloud.hasFolder("Trabajo/Proyectos"))
        assertEquals(listOf("MKCOL", "MKCOL", "PUT"), requests().map { it.method })
    }

    @Test
    fun `folders already seen in the listing are not created again`() {
        nextcloud.put("Recetas/Pan.md", "pan")
        remote.list()
        requests()

        remote.write(NotePath("Recetas/Flan.md"), "flan", WritePrecondition.MustNotExist)

        assertEquals(listOf("PUT"), requests().map { it.method })
    }

    @Test
    fun `a category deleted on the server after listing is created again`() {
        nextcloud.put("Recetas/Pan.md", "pan")
        remote.list()
        nextcloud.removeFolder("Recetas")

        remote.write(NotePath("Recetas/Flan.md"), "flan", WritePrecondition.MustNotExist)

        assertEquals("flan", nextcloud.contentOf("Recetas/Flan.md"))
    }

    @Test
    fun `falls back to PROPFIND when the server sends no ETag on PUT`() {
        nextcloud.sendEtagOnWrite = false

        val version = remote.write(NotePath("Ideas.md"), "ideas", WritePrecondition.MustNotExist)

        assertEquals(Version(nextcloud.etagOf("Ideas.md")!!), version)
    }

    // Deleting

    @Test
    fun `deletes with If-Match`() {
        nextcloud.put("Ideas.md", "v1")
        val etag = nextcloud.etagOf("Ideas.md")!!

        remote.delete(NotePath("Ideas.md"), WritePrecondition.MustMatch(Version(etag)))

        assertFalse("Ideas.md" in nextcloud.snapshot())
        assertEquals(etag, server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun `deleting a note changed by someone else is rejected`() {
        nextcloud.put("Ideas.md", "v1")
        val stale = nextcloud.etagOf("Ideas.md")!!
        nextcloud.put("Ideas.md", "v2")

        assertThrows<PreconditionFailedException> { remote.delete(NotePath("Ideas.md"), WritePrecondition.MustMatch(Version(stale))) }
        assertEquals("v2", nextcloud.contentOf("Ideas.md"))
    }

    @Test
    fun `deleting a missing note fails with not found`() {
        assertThrows<NoteNotFoundException> { remote.delete(NotePath("nope.md"), WritePrecondition.None) }
    }
}
