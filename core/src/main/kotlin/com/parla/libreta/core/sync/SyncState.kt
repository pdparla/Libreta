package com.parla.libreta.core.sync

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.Version

/**
 * Versions of the local and the remote copy of a note the last time both had the same content. This is the
 * common ancestor of the three-way comparison: it tells "changed here" apart from "changed there".
 */
data class SyncBase(val localVersion: Version, val remoteVersion: Version)

/** Persists the [SyncBase] of every synced note between runs. */
interface SyncStateStore {

    fun load(): Map<NotePath, SyncBase>

    fun save(state: Map<NotePath, SyncBase>)
}
