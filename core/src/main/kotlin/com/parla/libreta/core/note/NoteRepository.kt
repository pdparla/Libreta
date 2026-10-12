package com.parla.libreta.core.note

/**
 * Port to a store of the notes tree: Markdown notes and their attachments (see [NotePath]). There are two kinds
 * of implementation: the local files the editors work on, and the remote protocols
 * ([com.parla.libreta.core.sync.SyncStrategy]).
 *
 * Every version a store returns must change whenever the file's content changes, and
 * [WritePrecondition]s must be checked atomically together with the write.
 */
interface NoteRepository {

    /** Every note and attachment currently in the store, with its version. */
    fun list(): List<NoteEntry>

    /** @throws NoteNotFoundException if there is no file at [path]. */
    fun read(path: NotePath): VersionedContent

    /** Whether there is a file at [path]. Stores should override this when they can tell without reading the content. */
    fun exists(path: NotePath): Boolean = readOrNull(path) != null

    /**
     * Creates or replaces the file at [path] and returns its new version.
     *
     * @throws PreconditionFailedException if [precondition] does not hold.
     */
    fun write(path: NotePath, content: ByteArray, precondition: WritePrecondition = WritePrecondition.None): Version

    /**
     * @throws NoteNotFoundException if there is no file at [path].
     * @throws PreconditionFailedException if [precondition] does not hold.
     */
    fun delete(path: NotePath, precondition: WritePrecondition = WritePrecondition.None)
}

/** Like [NoteRepository.read], but returns null instead of throwing when the file does not exist. */
fun NoteRepository.readOrNull(path: NotePath): VersionedContent? =
    try {
        read(path)
    } catch (e: NoteNotFoundException) {
        null
    }

/** Writes [text] as UTF-8, the encoding of every note. */
fun NoteRepository.writeText(path: NotePath, text: String, precondition: WritePrecondition = WritePrecondition.None): Version =
    write(path, text.toByteArray(Charsets.UTF_8), precondition)
