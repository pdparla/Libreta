package com.parla.libreta.data.remote

import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NoteDraft
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteService
import com.parla.libreta.core.note.SaveResult
import com.parla.libreta.core.sync.SyncEngine
import com.parla.libreta.data.local.FileNoteRepository
import com.parla.libreta.data.local.FileSyncStateStore
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The real adapters together: files on disk, WebDAV over HTTP and the state file, driven by the SyncEngine. */
class SyncIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val nextcloud = FakeNextcloud()
    private lateinit var local: FileNoteRepository
    private lateinit var notes: NoteService
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        nextcloud.start(server)
        server.start()
        val client = OkHttpClient.Builder()
            .addInterceptor { it.proceed(it.request().newBuilder().header("Authorization", Credentials.basic("alice", "app-password")).build()) }
            .build()
        val remote = NextcloudWebDavSync(client, server.url(nextcloud.basePath))
        local = FileNoteRepository(File(temp.root, "notes"))
        val namer = ConflictNamer { "2026-10-11" }
        notes = NoteService(local, namer)
        engine = SyncEngine(local, remote, FileSyncStateStore(File(temp.root, "sync-state.tsv")), namer)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun localSnapshot(): Map<String, String> = local.list().associate { it.path.value to local.read(it.path).text }

    private fun write(content: String, category: String = ""): NotePath =
        (notes.save(NoteDraft.blank(category).edit(content)) as SaveResult.Saved).note.path

    private fun assertConverged() {
        assertEquals(nextcloud.snapshot(), localSnapshot())
        val again = engine.sync()
        assertTrue("second sync should be a no-op: $again", again.isComplete && again.changeCount == 0)
    }

    @Test
    fun `first sync merges both sides, categories included`() {
        write("Lista de la compra\n- pan")
        write("Pan integral\nharina", category = "Recetas")
        nextcloud.put("Desde el móvil.md", "escrita en el móvil")
        nextcloud.put("Trabajo/Proyectos/Plan.md", "plan")

        val report = engine.sync()

        assertTrue(report.isComplete)
        assertEquals("harina", nextcloud.contentOf("Recetas/Pan integral.md")!!.lines()[1])
        assertEquals("plan", local.read(NotePath("Trabajo/Proyectos/Plan.md")).text)
        assertConverged()
    }

    @Test
    fun `edits, moves and deletions travel both ways`() {
        val ideas = write("Ideas\nv1")
        write("Borrar\nx")
        engine.sync()

        notes.save(notes.open(ideas).edit("Ideas\nv2").moveTo("Trabajo"))
        notes.delete(NotePath("Borrar.md"))
        nextcloud.put("Nueva.md", "nueva")

        engine.sync()

        assertEquals(mapOf("Nueva.md" to "nueva", "Trabajo/Ideas.md" to "Ideas\nv2"), nextcloud.snapshot())
        assertConverged()
    }

    @Test
    fun `edits on both sides end up as a conflict copy on both sides`() {
        val ideas = write("Ideas\nv1")
        engine.sync()
        notes.save(notes.open(ideas).edit("Ideas\nfrom the tablet"))
        nextcloud.put("Ideas.md", "Ideas\nfrom the phone")

        val report = engine.sync()

        assertEquals(1, report.conflicts.size)
        assertEquals("Ideas\nfrom the phone", nextcloud.contentOf("Ideas.md"))
        assertEquals("Ideas\nfrom the tablet", nextcloud.contentOf("Ideas (conflicto 2026-10-11).md"))
        assertConverged()
    }

    @Test
    fun `a note open in an editor on the web waits, and the others still sync`() {
        val ideas = write("Ideas\nv1")
        engine.sync()
        nextcloud.locked += ideas.value
        notes.save(notes.open(ideas).edit("Ideas\nv2"))
        write("Compra\npan")

        val locked = engine.sync()

        assertEquals(null, locked.abortedBy)
        assertEquals(listOf(ideas), locked.failures.map { it.path })
        assertEquals("Compra\npan", nextcloud.contentOf("Compra.md"))
        nextcloud.locked.clear()
        engine.sync()
        assertConverged()
    }

    @Test
    fun `a missing notes folder aborts the sync without deleting local notes`() {
        write("Ideas\nv1")
        engine.sync()
        nextcloud.notesFolderExists = false

        val report = engine.sync()

        assertTrue(report.abortedBy != null)
        assertEquals(setOf("Ideas.md"), localSnapshot().keys)
    }
}
