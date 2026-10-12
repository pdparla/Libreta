package com.parla.libreta.core.sync

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.note.WritePrecondition

/**
 * Three-way reconciliation: compares each side with the last synced [SyncBase] and decides what to do.
 * A pure function with no I/O; [SyncEngine] carries out the actions.
 *
 * When one side edited a note and the other deleted it, the edit wins, so no text is ever lost by syncing.
 */
object SyncPlanner {

    private enum class Change { NONE, MODIFIED, DELETED }

    /** Actions for every path known to either side or to the stored state, sorted by path. Paths in sync are omitted. */
    fun plan(
        local: Map<NotePath, Version>,
        remote: Map<NotePath, Version>,
        base: Map<NotePath, SyncBase>,
    ): List<SyncAction> {
        val paths = HashSet<NotePath>((local.size + remote.size + base.size) * 2)
        paths.addAll(local.keys)
        paths.addAll(remote.keys)
        paths.addAll(base.keys)
        return paths
            .sorted()
            .mapNotNull { path -> planFor(path, local[path], remote[path], base[path]) }
    }

    private fun planFor(path: NotePath, local: Version?, remote: Version?, base: SyncBase?): SyncAction? {
        if (base == null) {
            return when {
                local != null && remote != null -> SyncAction.ResolveConflict(path)
                local != null -> SyncAction.Upload(path, WritePrecondition.MustNotExist)
                remote != null -> SyncAction.Download(path, WritePrecondition.MustNotExist)
                else -> null
            }
        }
        val localChange = changeOf(local, base.localVersion)
        val remoteChange = changeOf(remote, base.remoteVersion)
        return when (localChange) {
            Change.NONE -> when (remoteChange) {
                Change.NONE -> null
                Change.MODIFIED -> SyncAction.Download(path, WritePrecondition.MustMatch(base.localVersion))
                Change.DELETED -> SyncAction.DeleteLocal(path, base.localVersion)
            }
            Change.MODIFIED -> when (remoteChange) {
                Change.NONE -> SyncAction.Upload(path, WritePrecondition.MustMatch(base.remoteVersion))
                Change.MODIFIED -> SyncAction.ResolveConflict(path)
                Change.DELETED -> SyncAction.Upload(path, WritePrecondition.MustNotExist)
            }
            Change.DELETED -> when (remoteChange) {
                Change.NONE -> SyncAction.DeleteRemote(path, base.remoteVersion)
                Change.MODIFIED -> SyncAction.Download(path, WritePrecondition.MustNotExist)
                Change.DELETED -> SyncAction.Forget(path)
            }
        }
    }

    private fun changeOf(current: Version?, synced: Version): Change = when (current) {
        null -> Change.DELETED
        synced -> Change.NONE
        else -> Change.MODIFIED
    }
}
