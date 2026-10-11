package com.parla.libreta.core.note

/** Base class for every failure of a [NoteRepository]. */
open class NoteStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NoteNotFoundException(val path: NotePath) : NoteStoreException("Note not found: $path")

/** A [WritePrecondition] did not hold: the note was changed by someone else in between. */
class PreconditionFailedException(val path: NotePath) : NoteStoreException("Note changed concurrently: $path")

/** The store cannot be used right now (no network, server error, TLS, disk...). Retrying later may succeed. */
class StoreUnavailableException(message: String, cause: Throwable? = null) : NoteStoreException(message, cause)
