package com.parla.libreta.data.remote

import com.parla.libreta.core.note.NoteEntry

/**
 * Remembers, per folder of the server, what a complete listing found below it, keyed by the folder's collection ETag.
 * Nextcloud changes that ETag whenever anything inside the folder (at any depth) changes, so while the ETag is the
 * same the remembered subtree is still exact. The root is never cached: it is read on every listing.
 *
 * The map is immutable and swapped as a whole, so readers never see a half-updated cache.
 */
class ListingCache {

    /** Everything below a folder: [notes] at any depth and every synced sub-[folders] (relative paths). */
    internal class Subtree(
        val etag: String?,
        val notes: List<NoteEntry>,
        val folders: List<String>,
        /** False if this folder or any below it came without ETag; such a subtree is never stored. */
        val cacheable: Boolean,
    )

    internal class Snapshot(val version: Long, val folders: Map<String, Subtree>)

    private var folders: Map<String, Subtree> = emptyMap()
    private var version = 0L

    @Synchronized
    internal fun snapshot() = Snapshot(version, folders)

    /** Swaps in [fresh] unless an invalidation happened since [expectedVersion] was taken. */
    @Synchronized
    internal fun replace(expectedVersion: Long, fresh: Map<String, Subtree>) {
        if (version == expectedVersion) folders = fresh.toMap()
    }

    /** Forgets [folder] and its ancestors, which a write below them has changed on the server. */
    @Synchronized
    fun invalidate(folder: String) {
        val stale = mutableSetOf<String>()
        var current = folder
        while (current.isNotEmpty()) {
            stale += current
            current = current.substringBeforeLast('/', "")
        }
        folders = folders - stale
        version++
    }
}
