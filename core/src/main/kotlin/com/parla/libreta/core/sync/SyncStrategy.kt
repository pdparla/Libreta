package com.parla.libreta.core.sync

import com.parla.libreta.core.note.NoteRepository

/**
 * Remote side of the synchronisation: a [NoteRepository] implemented on top of a concrete protocol.
 * The first implementation is WebDAV against Nextcloud; the Nextcloud Notes API could be another.
 *
 * Contract on top of [NoteRepository]:
 * - versions are server-side change tokens (ETags), so a note edited by any client gets a new one;
 * - preconditions are enforced by the server, not checked client-side, which makes them race-free;
 * - transport failures (network, TLS, auth, 5xx) are reported as
 *   [com.parla.libreta.core.note.StoreUnavailableException].
 */
interface SyncStrategy : NoteRepository {

    /** Human-readable protocol name, for logs and the settings screen. */
    val name: String
}
