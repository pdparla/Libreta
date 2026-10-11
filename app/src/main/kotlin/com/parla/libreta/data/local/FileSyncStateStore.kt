package com.parla.libreta.data.local

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.StoreUnavailableException
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.sync.SyncBase
import com.parla.libreta.core.sync.SyncStateStore
import java.io.File
import java.io.IOException

/**
 * Sync state as a small tab-separated file: `path<TAB>localVersion<TAB>remoteVersion`, one note per line. Paths
 * and versions never contain tabs or line breaks: NotePath rejects control characters, and the versions are hashes
 * and ETags.
 *
 * A missing or unreadable file means "never synced". That is safe: the next sync compares the contents of both
 * sides, and only notes whose texts differ become conflict copies.
 */
class FileSyncStateStore(private val file: File) : SyncStateStore {

    override fun load(): Map<NotePath, SyncBase> {
        val lines = try {
            if (file.isFile) file.readLines(Charsets.UTF_8) else return emptyMap()
        } catch (e: IOException) {
            throw StoreUnavailableException("Cannot read sync state", e)
        }
        if (lines.firstOrNull() != HEADER) return emptyMap()
        return lines.drop(1).mapNotNull(::parse).toMap()
    }

    override fun save(state: Map<NotePath, SyncBase>) {
        val text = buildString {
            append(HEADER).append('\n')
            for ((path, base) in state.entries.sortedBy { it.key }) {
                append(path.value).append('\t')
                    .append(base.localVersion.value).append('\t')
                    .append(base.remoteVersion.value).append('\n')
            }
        }
        try {
            writeAtomically(file, text)
        } catch (e: IOException) {
            throw StoreUnavailableException("Cannot save sync state", e)
        }
    }

    /** Forgets everything, for example when the server account changes. */
    fun clear() {
        file.delete()
    }

    private fun parse(line: String): Pair<NotePath, SyncBase>? {
        val columns = line.split('\t')
        if (columns.size != 3 || columns.any { it.isEmpty() }) return null
        val path = NotePath.parseOrNull(columns[0]) ?: return null
        return path to SyncBase(Version(columns[1]), Version(columns[2]))
    }

    private companion object {
        const val HEADER = "# libreta sync state v1"
    }
}
