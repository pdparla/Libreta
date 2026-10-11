package com.parla.libreta.core.testing

import com.parla.libreta.core.note.ContentHash
import com.parla.libreta.core.note.NoteEntry
import com.parla.libreta.core.note.NoteNotFoundException
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.note.PreconditionFailedException
import com.parla.libreta.core.note.StoreUnavailableException
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.note.VersionedContent
import com.parla.libreta.core.note.WritePrecondition
import com.parla.libreta.core.sync.SyncBase
import com.parla.libreta.core.sync.SyncStateStore
import com.parla.libreta.core.sync.SyncStrategy
import java.util.TreeMap

/** In-memory [NoteRepository] that enforces preconditions; [mintVersion] decides how versions are generated. */
open class InMemoryNoteRepository(private val mintVersion: (ByteArray) -> Version) : NoteRepository {

    private val notes = TreeMap<NotePath, VersionedContent>()

    /** Called before every write or delete; lets tests simulate a concurrent edit or a network failure. */
    var beforeMutation: (NotePath) -> Unit = {}

    var writes = 0
        private set

    override fun list(): List<NoteEntry> = notes.map { (path, note) -> NoteEntry(path, note.version) }

    override fun read(path: NotePath): VersionedContent = notes[path] ?: throw NoteNotFoundException(path)

    override fun write(path: NotePath, content: ByteArray, precondition: WritePrecondition): Version {
        beforeMutation(path)
        check(path, precondition)
        val version = mintVersion(content)
        notes[path] = VersionedContent(content.copyOf(), version)
        writes++
        return version
    }

    override fun delete(path: NotePath, precondition: WritePrecondition) {
        beforeMutation(path)
        if (path !in notes) throw NoteNotFoundException(path)
        check(path, precondition)
        notes.remove(path)
    }

    private fun check(path: NotePath, precondition: WritePrecondition) {
        if (!precondition.isSatisfiedBy(notes[path]?.version)) throw PreconditionFailedException(path)
    }

    // Test helpers that bypass hooks and counters.

    fun put(path: String, content: String) = putBytes(path, content.toByteArray(Charsets.UTF_8))

    fun putBytes(path: String, content: ByteArray) {
        notes[NotePath(path)] = VersionedContent(content, mintVersion(content))
    }

    fun remove(path: String) {
        notes.remove(NotePath(path))
    }

    fun contentOf(path: String): String? = notes[NotePath(path)]?.text

    fun snapshot(): Map<String, String> = notes.entries.associate { (path, note) -> path.value to note.text }
}

/** Local side: versions are content hashes, like the file-based store will use. */
class FakeLocal : InMemoryNoteRepository({ ContentHash.of(it) })

/** Remote side: every write gets a fresh ETag, as on a WebDAV server. Can be switched offline. */
class FakeRemote : InMemoryNoteRepository(EtagSequence()), SyncStrategy {

    override val name = "fake"

    override fun checkConnection() = guarded {}

    var online = true

    /** Fail every call once this many more calls have been made. */
    var failAfterCalls = Int.MAX_VALUE

    override fun list(): List<NoteEntry> = guarded { super.list() }

    override fun read(path: NotePath): VersionedContent = guarded { super.read(path) }

    override fun write(path: NotePath, content: ByteArray, precondition: WritePrecondition): Version =
        guarded { super.write(path, content, precondition) }

    override fun delete(path: NotePath, precondition: WritePrecondition) = guarded { super.delete(path, precondition) }

    private fun <T> guarded(call: () -> T): T {
        if (!online || failAfterCalls-- <= 0) throw StoreUnavailableException("offline")
        return call()
    }
}

private class EtagSequence : (ByteArray) -> Version {
    private var next = 0
    override fun invoke(content: ByteArray) = Version("\"etag-${++next}\"")
}

class InMemorySyncStateStore : SyncStateStore {

    var state: Map<NotePath, SyncBase> = emptyMap()
        private set

    var saves = 0
        private set

    override fun load(): Map<NotePath, SyncBase> = state

    override fun save(state: Map<NotePath, SyncBase>) {
        this.state = state.toMap()
        saves++
    }
}
