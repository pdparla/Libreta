package com.parla.libreta.core.sync

import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.note.NoteNotFoundException
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.core.note.PreconditionFailedException
import com.parla.libreta.core.note.StoreUnavailableException
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.note.VersionedContent
import com.parla.libreta.core.note.WritePrecondition
import com.parla.libreta.core.note.readOrNull

/**
 * Brings [local] and [remote] in line: lists both, lets [SyncPlanner] decide, and carries out the actions with
 * optimistic concurrency. A write that loses a race (the note changed after it was listed) is never forced; it
 * is resolved as a conflict or left for the next run.
 *
 * Progress is saved even if the run stops halfway, so an interrupted sync resumes where it stopped. Runs are
 * serialised: the app may trigger them on open, on close and from a button at the same time.
 */
class SyncEngine(
    private val local: NoteRepository,
    private val remote: SyncStrategy,
    private val stateStore: SyncStateStore,
    private val conflictNamer: ConflictNamer,
) {

    @Synchronized
    fun sync(): SyncReport {
        val state = stateStore.load().toMutableMap()
        val run = Run(state)
        try {
            val localVersions = local.list().associate { it.path to it.version }
            val remoteVersions = remote.list().associate { it.path to it.version }
            run.knownPaths += localVersions.keys
            run.knownPaths += remoteVersions.keys
            for (action in SyncPlanner.plan(localVersions, remoteVersions, state)) {
                run.execute(action)
            }
        } catch (e: StoreUnavailableException) {
            run.abortedBy = e
        } finally {
            stateStore.save(state)
        }
        return run.report()
    }

    /** Mutable bookkeeping of a single [sync] call. */
    private inner class Run(private val state: MutableMap<NotePath, SyncBase>) {
        val knownPaths = mutableSetOf<NotePath>()
        var abortedBy: StoreUnavailableException? = null
        private val uploaded = mutableListOf<NotePath>()
        private val downloaded = mutableListOf<NotePath>()
        private val deletedLocally = mutableListOf<NotePath>()
        private val deletedRemotely = mutableListOf<NotePath>()
        private val conflicts = mutableListOf<SyncConflict>()
        private val failures = mutableListOf<SyncFailure>()

        fun report() = SyncReport(uploaded, downloaded, deletedLocally, deletedRemotely, conflicts, failures, abortedBy)

        /** Runs one action. An unavailable store aborts the whole run; any other store error only skips this note. */
        fun execute(action: SyncAction) {
            try {
                when (action) {
                    is SyncAction.Upload -> upload(action.path, action.remotePrecondition)
                    is SyncAction.Download -> download(action.path, action.localPrecondition)
                    is SyncAction.DeleteRemote -> deleteRemote(action.path, action.expected)
                    is SyncAction.DeleteLocal -> deleteLocal(action.path, action.expected)
                    is SyncAction.ResolveConflict -> resolveConflict(action.path)
                    is SyncAction.Forget -> state.remove(action.path)
                }
            } catch (e: StoreUnavailableException) {
                throw e
            } catch (e: NoteStoreException) {
                failures += SyncFailure(action.path, e)
            }
        }

        private fun upload(path: NotePath, remotePrecondition: WritePrecondition) {
            val mine = local.readOrNull(path) ?: return // Deleted locally after listing: the next run sees it.
            val remoteVersion = try {
                remote.write(path, mine.bytes, remotePrecondition)
            } catch (e: PreconditionFailedException) {
                return resolveConflict(path) // The remote changed after listing.
            }
            recordUpload(path, mine.version, remoteVersion)
        }

        private fun download(path: NotePath, localPrecondition: WritePrecondition) {
            val theirs = remote.readOrNull(path) ?: return // Deleted remotely after listing: the next run sees it.
            val localVersion = local.write(path, theirs.bytes, localPrecondition)
            recordDownload(path, localVersion, theirs.version)
        }

        private fun deleteRemote(path: NotePath, expected: Version) {
            try {
                remote.delete(path, WritePrecondition.MustMatch(expected))
            } catch (e: NoteNotFoundException) {
                // Already gone: nothing left to do.
            } catch (e: PreconditionFailedException) {
                // Edited remotely after listing; an edit beats a deletion, so bring it back.
                return download(path, WritePrecondition.MustNotExist)
            }
            state.remove(path)
            deletedRemotely += path
        }

        private fun deleteLocal(path: NotePath, expected: Version) {
            try {
                local.delete(path, WritePrecondition.MustMatch(expected))
            } catch (e: NoteNotFoundException) {
                // Already gone: nothing left to do.
            }
            state.remove(path)
            deletedLocally += path
        }

        private fun resolveConflict(path: NotePath) {
            val mine = local.readOrNull(path)
            val theirs = remote.readOrNull(path)
            if (mine == null || theirs == null) {
                // One side vanished while we looked: whatever content is left is kept on both sides.
                when {
                    mine != null -> recordUpload(
                        path, mine.version, remote.write(path, mine.bytes, WritePrecondition.MustNotExist),
                    )
                    theirs != null -> recordDownload(
                        path, local.write(path, theirs.bytes, WritePrecondition.MustNotExist), theirs.version,
                    )
                    else -> state.remove(path)
                }
            } else if (mine.sameContentAs(theirs)) {
                state[path] = SyncBase(mine.version, theirs.version)
            } else {
                keepBoth(path, mine, theirs)
            }
        }

        /**
         * The remote version keeps the original name, because other devices already show it. The local one moves to
         * a conflict copy, written locally first so it survives a failure in any later step.
         */
        private fun keepBoth(path: NotePath, mine: VersionedContent, theirs: VersionedContent) {
            val copy = conflictNamer.conflictPathFor(path) { it in knownPaths }
            knownPaths += copy
            val copyLocalVersion = local.write(copy, mine.bytes, WritePrecondition.MustNotExist)
            val copyRemoteVersion = remote.write(copy, mine.bytes, WritePrecondition.MustNotExist)
            state[copy] = SyncBase(copyLocalVersion, copyRemoteVersion)

            val localVersion = local.write(path, theirs.bytes, WritePrecondition.MustMatch(mine.version))
            state[path] = SyncBase(localVersion, theirs.version)
            conflicts += SyncConflict(original = path, copy = copy)
        }

        private fun recordUpload(path: NotePath, localVersion: Version, remoteVersion: Version) {
            state[path] = SyncBase(localVersion, remoteVersion)
            uploaded += path
        }

        private fun recordDownload(path: NotePath, localVersion: Version, remoteVersion: Version) {
            state[path] = SyncBase(localVersion, remoteVersion)
            downloaded += path
        }
    }
}
