package com.parla.libreta.core.sync

import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.PreconditionFailedException
import com.parla.libreta.core.testing.FakeLocal
import com.parla.libreta.core.testing.FakeRemote
import com.parla.libreta.core.testing.InMemorySyncStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val local = FakeLocal()
    private val remote = FakeRemote()
    private val stateStore = InMemorySyncStateStore()
    private val engine = SyncEngine(local, remote, stateStore, ConflictNamer { "2026-10-11" })

    private fun path(value: String) = NotePath(value)

    /** Puts the same notes on both sides and syncs once, leaving them in a known synced state. */
    private fun givenSynced(vararg notes: Pair<String, String>) {
        notes.forEach { (path, content) -> local.put(path, content) }
        engine.sync()
        assertEquals(local.snapshot(), remote.snapshot())
    }

    private fun assertConverged() {
        assertEquals("local and remote differ", local.snapshot(), remote.snapshot())
        val again = engine.sync()
        assertEquals("a second sync should have nothing to do", 0, again.changeCount)
        assertTrue(again.isComplete)
    }

    // First sync

    @Test
    fun `first sync uploads local notes and downloads remote ones`() {
        local.put("Local.md", "from the tablet")
        local.put("Recetas/Pan.md", "harina, agua")
        remote.put("Remote.md", "from the server")

        val report = engine.sync()

        assertEquals(listOf(path("Local.md"), path("Recetas/Pan.md")), report.uploaded)
        assertEquals(listOf(path("Remote.md")), report.downloaded)
        assertEquals("from the server", local.contentOf("Remote.md"))
        assertConverged()
    }

    @Test
    fun `a note created on both sides with the same text is not a conflict`() {
        local.put("Ideas.md", "same")
        remote.put("Ideas.md", "same")

        val report = engine.sync()

        assertTrue(report.conflicts.isEmpty())
        assertEquals(0, remote.writes)
        assertConverged()
    }

    @Test
    fun `a note created on both sides with different text keeps both`() {
        local.put("Ideas.md", "mine")
        remote.put("Ideas.md", "theirs")

        val report = engine.sync()

        val copy = path("Ideas (conflicto 2026-10-11).md")
        assertEquals(listOf(SyncConflict(path("Ideas.md"), copy)), report.conflicts)
        assertEquals("theirs", local.contentOf("Ideas.md"))
        assertEquals("mine", local.contentOf(copy.value))
        assertConverged()
    }

    @Test
    fun `attachments sync as raw bytes both ways`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 0xff.toByte(), 0x0a)
        local.putBytes("Dibujos/.attachments.k3/pagina-1.png", png)
        remote.putBytes(".attachments.99/foto.jpg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0))

        engine.sync()

        assertTrue(png.contentEquals(remote.read(path("Dibujos/.attachments.k3/pagina-1.png")).bytes))
        assertTrue(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0).contentEquals(local.read(path(".attachments.99/foto.jpg")).bytes))
        assertConverged()
    }

    // One side changed

    @Test
    fun `local edit is uploaded`() {
        givenSynced("Ideas.md" to "v1")
        local.put("Ideas.md", "v2")

        val report = engine.sync()

        assertEquals(listOf(path("Ideas.md")), report.uploaded)
        assertEquals("v2", remote.contentOf("Ideas.md"))
        assertConverged()
    }

    @Test
    fun `remote edit is downloaded`() {
        givenSynced("Ideas.md" to "v1")
        remote.put("Ideas.md", "v2")

        val report = engine.sync()

        assertEquals(listOf(path("Ideas.md")), report.downloaded)
        assertEquals("v2", local.contentOf("Ideas.md"))
        assertConverged()
    }

    @Test
    fun `local deletion is propagated`() {
        givenSynced("Ideas.md" to "v1", "Otra.md" to "x")
        local.remove("Ideas.md")

        val report = engine.sync()

        assertEquals(listOf(path("Ideas.md")), report.deletedRemotely)
        assertNull(remote.contentOf("Ideas.md"))
        assertConverged()
    }

    @Test
    fun `remote deletion is propagated`() {
        givenSynced("Ideas.md" to "v1")
        remote.remove("Ideas.md")

        val report = engine.sync()

        assertEquals(listOf(path("Ideas.md")), report.deletedLocally)
        assertNull(local.contentOf("Ideas.md"))
        assertConverged()
    }

    // Both sides changed

    @Test
    fun `edits on both sides keep both texts`() {
        givenSynced("Ideas.md" to "v1")
        local.put("Ideas.md", "local v2")
        remote.put("Ideas.md", "remote v2")

        val report = engine.sync()

        val copy = "Ideas (conflicto 2026-10-11).md"
        assertEquals(listOf(SyncConflict(path("Ideas.md"), path(copy))), report.conflicts)
        assertEquals("remote v2", local.contentOf("Ideas.md"))
        assertEquals("remote v2", remote.contentOf("Ideas.md"))
        assertEquals("local v2", local.contentOf(copy))
        assertEquals("local v2", remote.contentOf(copy))
        assertConverged()
    }

    @Test
    fun `conflict copy in a category stays in that category`() {
        givenSynced("Recetas/Pan.md" to "v1")
        local.put("Recetas/Pan.md", "local")
        remote.put("Recetas/Pan.md", "remote")

        engine.sync()

        assertEquals("local", remote.contentOf("Recetas/Pan (conflicto 2026-10-11).md"))
        assertConverged()
    }

    @Test
    fun `conflict copy gets a counter when today's copy already exists`() {
        givenSynced("Ideas.md" to "v1", "Ideas (conflicto 2026-10-11).md" to "earlier conflict")
        local.put("Ideas.md", "local")
        remote.put("Ideas.md", "remote")

        engine.sync()

        assertEquals("earlier conflict", remote.contentOf("Ideas (conflicto 2026-10-11).md"))
        assertEquals("local", remote.contentOf("Ideas (conflicto 2026-10-11) 2.md"))
        assertConverged()
    }

    @Test
    fun `identical edits on both sides are not a conflict`() {
        givenSynced("Ideas.md" to "v1")
        local.put("Ideas.md", "v2")
        remote.put("Ideas.md", "v2")

        val report = engine.sync()

        assertTrue(report.conflicts.isEmpty())
        assertConverged()
    }

    @Test
    fun `local edit wins over remote deletion`() {
        givenSynced("Ideas.md" to "v1")
        local.put("Ideas.md", "v2")
        remote.remove("Ideas.md")

        engine.sync()

        assertEquals("v2", remote.contentOf("Ideas.md"))
        assertConverged()
    }

    @Test
    fun `remote edit wins over local deletion`() {
        givenSynced("Ideas.md" to "v1")
        local.remove("Ideas.md")
        remote.put("Ideas.md", "v2")

        engine.sync()

        assertEquals("v2", local.contentOf("Ideas.md"))
        assertConverged()
    }

    // Races and failures

    @Test
    fun `upload that loses a race with a remote edit becomes a conflict instead of overwriting`() {
        givenSynced("Ideas.md" to "v1")
        local.put("Ideas.md", "local v2")
        var raced = false
        remote.beforeMutation = { p ->
            if (!raced && p == path("Ideas.md")) {
                raced = true
                remote.put("Ideas.md", "remote v2, saved while we were syncing")
            }
        }

        val report = engine.sync()

        assertEquals(1, report.conflicts.size)
        assertEquals("remote v2, saved while we were syncing", remote.contentOf("Ideas.md"))
        assertEquals("local v2", remote.contentOf("Ideas (conflicto 2026-10-11).md"))
        assertConverged()
    }

    @Test
    fun `remote deletion that loses a race with a remote edit restores the note`() {
        givenSynced("Ideas.md" to "v1")
        local.remove("Ideas.md")
        var raced = false
        remote.beforeMutation = {
            if (!raced) {
                raced = true
                remote.put("Ideas.md", "edited on the phone meanwhile")
            }
        }

        engine.sync()

        assertEquals("edited on the phone meanwhile", local.contentOf("Ideas.md"))
        assertConverged()
    }

    @Test
    fun `typing during a download is never overwritten`() {
        givenSynced("Ideas.md" to "v1")
        remote.put("Ideas.md", "remote v2")
        local.beforeMutation = { local.put("Ideas.md", "typed during sync") }

        val report = engine.sync()

        assertEquals("typed during sync", local.contentOf("Ideas.md"))
        assertTrue(report.failures.single().error is PreconditionFailedException)

        local.beforeMutation = {}
        val next = engine.sync()
        assertEquals(1, next.conflicts.size)
        assertEquals("typed during sync", local.contentOf("Ideas (conflicto 2026-10-11).md"))
        assertConverged()
    }

    @Test
    fun `offline sync aborts without touching anything`() {
        givenSynced("Ideas.md" to "v1")
        local.put("Ideas.md", "v2")
        val stateBefore = stateStore.state
        remote.online = false

        val report = engine.sync()

        assertNotNull(report.abortedBy)
        assertEquals(0, report.changeCount)
        assertEquals("v2", local.contentOf("Ideas.md"))
        assertEquals(stateBefore, stateStore.state)

        remote.online = true
        engine.sync()
        assertEquals("v2", remote.contentOf("Ideas.md"))
    }

    @Test
    fun `connection lost halfway keeps progress and the next sync finishes without duplicates`() {
        local.put("a.md", "a")
        local.put("b.md", "b")
        local.put("c.md", "c")
        remote.failAfterCalls = 3 // list, upload a, upload b, then the network drops

        val first = engine.sync()

        assertNotNull(first.abortedBy)
        assertEquals(listOf(path("a.md"), path("b.md")), first.uploaded)
        assertEquals(setOf(path("a.md"), path("b.md")), stateStore.state.keys)

        remote.failAfterCalls = Int.MAX_VALUE
        val second = engine.sync()

        assertEquals(listOf(path("c.md")), second.uploaded)
        assertEquals(3, remote.writes)
        assertConverged()
    }

    // State persistence

    @Test
    fun `a run that changes nothing does not rewrite the state`() {
        givenSynced("a.md" to "a", "b.md" to "b")
        val savesBefore = stateStore.saves

        val report = engine.sync()

        assertEquals(0, report.changeCount)
        assertEquals(savesBefore, stateStore.saves)
    }

    @Test
    fun `an empty first run does not write the state`() {
        engine.sync()

        assertEquals(0, stateStore.saves)
    }

    @Test
    fun `a run that changes something saves the state`() {
        givenSynced("a.md" to "a")
        val savesBefore = stateStore.saves

        local.put("a.md", "a, edited")
        engine.sync()

        assertEquals(savesBefore + 1, stateStore.saves)
    }

    @Test
    fun `forgetting a note deleted on both sides saves the state`() {
        givenSynced("a.md" to "a")
        local.remove("a.md")
        remote.remove("a.md")
        val savesBefore = stateStore.saves

        engine.sync()

        assertEquals(savesBefore + 1, stateStore.saves)
        assertTrue(stateStore.state.isEmpty())
    }

    @Test
    fun `a run aborted after some progress still saves the state`() {
        givenSynced("a.md" to "a")
        val savesBefore = stateStore.saves
        local.put("b.md", "b")
        local.put("c.md", "c")
        remote.failAfterCalls = 2 // list, upload b, then the network drops

        val report = engine.sync()

        assertNotNull(report.abortedBy)
        assertEquals(savesBefore + 1, stateStore.saves)
        assertEquals(setOf(path("a.md"), path("b.md")), stateStore.state.keys)
    }

    @Test
    fun `a run aborted before any progress does not rewrite the state`() {
        givenSynced("a.md" to "a")
        val savesBefore = stateStore.saves
        remote.online = false

        engine.sync()

        assertEquals(savesBefore, stateStore.saves)
    }
}
