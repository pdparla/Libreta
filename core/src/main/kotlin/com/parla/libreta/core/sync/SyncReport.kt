package com.parla.libreta.core.sync

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteStoreException

/** Outcome of one [SyncEngine.sync] run. */
data class SyncReport(
    val uploaded: List<NotePath> = emptyList(),
    val downloaded: List<NotePath> = emptyList(),
    val deletedLocally: List<NotePath> = emptyList(),
    val deletedRemotely: List<NotePath> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
    val failures: List<SyncFailure> = emptyList(),
    /** Set when a store became unavailable and the run stopped early; the remaining work is retried next run. */
    val abortedBy: NoteStoreException? = null,
) {
    val isComplete: Boolean get() = abortedBy == null && failures.isEmpty()

    val changeCount: Int
        get() = uploaded.size + downloaded.size + deletedLocally.size + deletedRemotely.size + conflicts.size
}

/** Both sides changed [original]: the remote text stayed there and the local text was saved as [copy]. */
data class SyncConflict(val original: NotePath, val copy: NotePath)

/** A note that could not be synced this run; it is retried on the next one. */
data class SyncFailure(val path: NotePath, val error: NoteStoreException)
