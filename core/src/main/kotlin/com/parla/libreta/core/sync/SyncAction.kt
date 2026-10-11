package com.parla.libreta.core.sync

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.note.WritePrecondition

/** One step needed to bring a note back in sync, as decided by [SyncPlanner]. */
sealed class SyncAction {

    abstract val path: NotePath

    /** Send the local copy to the remote, which must still be as described by [remotePrecondition]. */
    data class Upload(override val path: NotePath, val remotePrecondition: WritePrecondition) : SyncAction()

    /** Fetch the remote copy and store it locally, if the local copy is still as described by [localPrecondition]. */
    data class Download(override val path: NotePath, val localPrecondition: WritePrecondition) : SyncAction()

    /** Delete the remote copy, unless it changed since [expected]. */
    data class DeleteRemote(override val path: NotePath, val expected: Version) : SyncAction()

    /** Delete the local copy, unless it changed since [expected]. */
    data class DeleteLocal(override val path: NotePath, val expected: Version) : SyncAction()

    /** Both copies changed or were created independently: keep both unless their contents are equal. */
    data class ResolveConflict(override val path: NotePath) : SyncAction()

    /** Both copies are gone: drop the stored [SyncBase]. */
    data class Forget(override val path: NotePath) : SyncAction()
}
