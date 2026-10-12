package com.parla.libreta.data.local

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
import java.io.File
import java.io.IOException

/** What the note list shows. Content is not loaded, so the list stays cheap on a 1 GB tablet. */
data class NoteSummary(val path: NotePath, val modifiedAt: Long)

/**
 * The notes tree as files under [root], the same layout as Nextcloud's notes folder: one sub-folder per category,
 * attachments in `.attachments.<id>/` folders next to their note. Versions are content hashes.
 *
 * Every operation holds one lock, so checking a precondition and writing are atomic between the editors and the
 * sync thread.
 */
class FileNoteRepository(private val root: File) : NoteRepository {

    private val lock = Any()

    /**
     * Content hashes keyed by path, valid while the file's modification time and size do not change. Hashing every
     * page image on every sync would be slow on this tablet. Safe because every write goes through this class,
     * which updates the entry; nothing else writes into the app's private folder.
     */
    private val hashes = HashMap<NotePath, CachedHash>()

    private data class CachedHash(val modifiedAt: Long, val size: Long, val version: Version)

    /** Notes only, without attachments. */
    fun summaries(): List<NoteSummary> = synchronized(lock) {
        files().filter { (path, _) -> path.isNote }.map { (path, file) -> NoteSummary(path, file.lastModified()) }
    }

    /** Whether there is a file at [path], without reading or hashing it. */
    override fun exists(path: NotePath): Boolean = synchronized(lock) { fileOf(path).isFile }

    override fun list(): List<NoteEntry> = synchronized(lock) {
        val found = files()
        hashes.keys.retainAll(found.map { it.first }.toSet())
        found.map { (path, file) -> NoteEntry(path, versionOf(path, file)) }
    }

    override fun read(path: NotePath): VersionedContent = synchronized(lock) {
        val file = fileOf(path)
        if (!file.isFile) throw NoteNotFoundException(path)
        val bytes = readBytes(file)
        VersionedContent(bytes, versionOfRead(path, file, bytes))
    }

    override fun write(path: NotePath, content: ByteArray, precondition: WritePrecondition): Version = synchronized(lock) {
        val file = fileOf(path)
        checkPrecondition(path, file, precondition)
        io("write $path") { writeAtomically(file, content) }
        val version = ContentHash.of(content)
        hashes[path] = CachedHash(file.lastModified(), file.length(), version)
        version
    }

    override fun delete(path: NotePath, precondition: WritePrecondition): Unit = synchronized(lock) {
        val file = fileOf(path)
        if (!file.isFile) throw NoteNotFoundException(path)
        checkPrecondition(path, file, precondition)
        if (!file.delete()) throw StoreUnavailableException("Cannot delete $path")
        hashes.remove(path)
        removeEmptyFolders(file.parentFile)
    }

    private fun checkPrecondition(path: NotePath, file: File, precondition: WritePrecondition) {
        if (precondition == WritePrecondition.None) return
        val current = if (file.isFile) versionOf(path, file) else null
        if (!precondition.isSatisfiedBy(current)) throw PreconditionFailedException(path)
    }

    private fun versionOf(path: NotePath, file: File): Version = cachedVersion(path, file) ?: hashAndCache(path, file, readBytes(file))

    /** The cached version of [path], or null if there is none or [file] changed since. */
    private fun cachedVersion(path: NotePath, file: File): Version? {
        val cached = hashes[path] ?: return null
        return if (cached.modifiedAt == file.lastModified() && cached.size == file.length()) cached.version else null
    }

    /**
     * The version of [bytes], just read from [file]: the cached one if the file looks unchanged, otherwise the hash,
     * which is cached. Reading a note therefore hashes it only the first time after a change.
     */
    private fun versionOfRead(path: NotePath, file: File, bytes: ByteArray): Version =
        cachedVersion(path, file) ?: hashAndCache(path, file, bytes)

    private fun hashAndCache(path: NotePath, file: File, bytes: ByteArray): Version {
        val version = ContentHash.of(bytes)
        hashes[path] = CachedHash(file.lastModified(), file.length(), version)
        return version
    }

    /** Every note and attachment below [root]; anything else (temp files, other hidden folders) is skipped. */
    private fun files(): List<Pair<NotePath, File>> {
        val found = mutableListOf<Pair<NotePath, File>>()
        fun walk(dir: File, relative: String) {
            for (child in dir.listFiles().orEmpty()) {
                val childPath = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                if (child.isDirectory) {
                    if (NotePath.isSyncedFolder(childPath)) walk(child, childPath)
                } else {
                    NotePath.parseOrNull(childPath)?.let { found += it to child }
                }
            }
        }
        walk(root, "")
        return found
    }

    /** Deleting a folder's last file removes the folder too, as Nextcloud Notes does with categories. */
    private fun removeEmptyFolders(start: File?) {
        var dir = start
        while (dir != null && dir != root && dir.list()?.isEmpty() == true && dir.delete()) {
            dir = dir.parentFile
        }
    }

    // NotePath rejects "..", absolute paths and backslashes, so the file is always inside root.
    private fun fileOf(path: NotePath) = File(root, path.value)

    private fun readBytes(file: File): ByteArray = io("read $file") { file.readBytes() }

    private inline fun <T> io(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw StoreUnavailableException("Cannot $what", e)
        }
}
