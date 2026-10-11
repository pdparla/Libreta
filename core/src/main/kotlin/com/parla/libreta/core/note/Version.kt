package com.parla.libreta.core.note

/**
 * Opaque version token for one copy of a note: a content hash for local files, the ETag for remote ones.
 * The only valid operation is comparing two tokens from the same store for equality.
 */
data class Version(val value: String) {

    init {
        require(value.isNotEmpty()) { "Version must not be empty" }
    }

    override fun toString(): String = value
}

/** A note as returned by a listing: its path and current version, without its content. */
data class NoteEntry(val path: NotePath, val version: Version)

/** Content of a note together with the version it was read at. */
data class VersionedContent(val content: String, val version: Version)
