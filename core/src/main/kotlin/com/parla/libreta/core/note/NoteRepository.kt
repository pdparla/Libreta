package com.parla.libreta.core.note

/**
 * Port to a store of Markdown notes. There are two kinds of implementation: the local `.md` files the
 * editor works on, and the remote protocols ([com.parla.libreta.core.sync.SyncStrategy]).
 *
 * Every version a store returns must change whenever the note's content changes, and
 * [WritePrecondition]s must be checked atomically together with the write.
 */
interface NoteRepository {

    /** Every note currently in the store, with its version. */
    fun list(): List<NoteEntry>

    /** @throws NoteNotFoundException if there is no note at [path]. */
    fun read(path: NotePath): VersionedContent

    /**
     * Creates or replaces the note at [path] and returns its new version.
     *
     * @throws PreconditionFailedException if [precondition] does not hold.
     */
    fun write(path: NotePath, content: String, precondition: WritePrecondition = WritePrecondition.None): Version

    /**
     * @throws NoteNotFoundException if there is no note at [path].
     * @throws PreconditionFailedException if [precondition] does not hold.
     */
    fun delete(path: NotePath, precondition: WritePrecondition = WritePrecondition.None)
}

/** Like [NoteRepository.read], but returns null instead of throwing when the note does not exist. */
fun NoteRepository.readOrNull(path: NotePath): VersionedContent? =
    try {
        read(path)
    } catch (e: NoteNotFoundException) {
        null
    }
