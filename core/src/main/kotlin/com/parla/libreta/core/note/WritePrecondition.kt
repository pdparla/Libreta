package com.parla.libreta.core.note

/** Optimistic-concurrency guard for writes and deletes. Maps to HTTP `If-None-Match: *` and `If-Match`. */
sealed class WritePrecondition {

    /** Write unconditionally. */
    data object None : WritePrecondition()

    /** Create only: fail if the note already exists. */
    data object MustNotExist : WritePrecondition()

    /** Replace or delete only this exact version of the note. */
    data class MustMatch(val version: Version) : WritePrecondition()

    /** Whether a note currently at [current] (null if absent) satisfies this precondition. */
    fun isSatisfiedBy(current: Version?): Boolean = when (this) {
        None -> true
        MustNotExist -> current == null
        is MustMatch -> current == version
    }
}
