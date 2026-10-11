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
 * Notes stored as `.md`/`.txt` files under [root], one sub-folder per category, the same layout as Nextcloud's
 * `Notes/` folder. Versions are content hashes.
 *
 * Every operation holds one lock, so checking a precondition and writing are atomic between the editor and the
 * sync thread.
 */
class FileNoteRepository(private val root: File) : NoteRepository {

    private val lock = Any()

    fun summaries(): List<NoteSummary> = synchronized(lock) {
        noteFiles().map { (path, file) -> NoteSummary(path, file.lastModified()) }
    }

    override fun list(): List<NoteEntry> = synchronized(lock) {
        noteFiles().map { (path, file) -> NoteEntry(path, ContentHash.of(readText(file))) }
    }

    override fun read(path: NotePath): VersionedContent = synchronized(lock) {
        val file = fileOf(path)
        if (!file.isFile) throw NoteNotFoundException(path)
        val content = readText(file)
        VersionedContent(content, ContentHash.of(content))
    }

    override fun write(path: NotePath, content: String, precondition: WritePrecondition): Version = synchronized(lock) {
        val file = fileOf(path)
        checkPrecondition(path, file, precondition)
        io("write $path") { writeAtomically(file, content) }
        ContentHash.of(content)
    }

    override fun delete(path: NotePath, precondition: WritePrecondition): Unit = synchronized(lock) {
        val file = fileOf(path)
        if (!file.isFile) throw NoteNotFoundException(path)
        checkPrecondition(path, file, precondition)
        if (!file.delete()) throw StoreUnavailableException("Cannot delete $path")
        removeEmptyCategories(file.parentFile)
    }

    private fun checkPrecondition(path: NotePath, file: File, precondition: WritePrecondition) {
        if (precondition == WritePrecondition.None) return
        val current = if (file.isFile) ContentHash.of(readText(file)) else null
        if (!precondition.isSatisfiedBy(current)) throw PreconditionFailedException(path)
    }

    /** Every note file below [root]. Hidden entries (temp files, `.attachments.*`) and other files are skipped. */
    private fun noteFiles(): List<Pair<NotePath, File>> {
        val found = mutableListOf<Pair<NotePath, File>>()
        fun walk(dir: File, prefix: String) {
            for (child in dir.listFiles().orEmpty()) {
                if (child.name.startsWith('.')) continue
                val relative = prefix + child.name
                if (child.isDirectory) {
                    walk(child, "$relative/")
                } else {
                    NotePath.parseOrNull(relative)?.let { found += it to child }
                }
            }
        }
        walk(root, "")
        return found
    }

    /** Deleting a category's last note removes the folder too, as Nextcloud Notes does. */
    private fun removeEmptyCategories(start: File?) {
        var dir = start
        while (dir != null && dir != root && dir.list()?.isEmpty() == true && dir.delete()) {
            dir = dir.parentFile
        }
    }

    // NotePath rejects "..", absolute paths and backslashes, so the file is always inside root.
    private fun fileOf(path: NotePath) = File(root, path.value)

    private fun readText(file: File): String = io("read $file") { file.readText(Charsets.UTF_8) }

    private inline fun <T> io(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw StoreUnavailableException("Cannot $what", e)
        }
}
