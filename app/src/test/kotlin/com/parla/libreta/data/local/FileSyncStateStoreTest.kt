package com.parla.libreta.data.local

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.sync.SyncBase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSyncStateStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val file: File by lazy { File(temp.root, "state/sync-state.tsv") }
    private val store by lazy { FileSyncStateStore(file) }

    private val state = mapOf(
        NotePath("Ideas.md") to SyncBase(Version("sha1:abc"), Version("\"5f2a\"")),
        NotePath("Recetas/Pan con espacios.md") to SyncBase(Version("sha1:def"), Version("W/\"xyz\"")),
    )

    @Test
    fun `missing file means never synced`() = assertEquals(emptyMap<NotePath, SyncBase>(), store.load())

    @Test
    fun `save and load round-trip`() {
        store.save(state)

        assertEquals(state, FileSyncStateStore(file).load())
    }

    @Test
    fun `saving replaces the previous state`() {
        store.save(state)
        store.save(emptyMap())

        assertEquals(emptyMap<NotePath, SyncBase>(), store.load())
    }

    @Test
    fun `unknown format is treated as never synced`() {
        file.parentFile?.mkdirs()
        file.writeText("something else\nIdeas.md\tsha1:abc\t\"1\"\n")

        assertEquals(emptyMap<NotePath, SyncBase>(), store.load())
    }

    @Test
    fun `malformed lines are skipped`() {
        store.save(state)
        file.appendText("broken line\n../escape.md\ta\tb\nx.md\t\tb\n")

        assertEquals(state, store.load())
    }

    @Test
    fun `clear forgets everything`() {
        store.save(state)
        store.clear()

        assertEquals(emptyMap<NotePath, SyncBase>(), store.load())
    }

    @Test
    fun `load after save returns the saved state`() {
        store.save(state)

        assertEquals(state, store.load())
        assertEquals(state, store.load())
    }

    @Test
    fun `a file changed behind the store's back is read again`() {
        store.save(state)
        assertEquals(state, store.load())

        file.writeText("# libreta sync state v1\nOther.md\tsha1:1\t\"2\"\n")

        assertEquals(mapOf(NotePath("Other.md") to SyncBase(Version("sha1:1"), Version("\"2\""))), store.load())
    }

    @Test
    fun `a deleted file is not served from memory`() {
        store.save(state)
        assertEquals(state, store.load())

        file.delete()

        assertEquals(emptyMap<NotePath, SyncBase>(), store.load())
    }
}
