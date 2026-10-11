package com.parla.libreta.core.note

/**
 * Opaque version token for one copy of a file: a content hash for local files, the ETag for remote ones.
 * The only valid operation is comparing two tokens from the same store for equality.
 */
data class Version(val value: String) {

    init {
        require(value.isNotEmpty()) { "Version must not be empty" }
    }

    override fun toString(): String = value
}

/** A file as returned by a listing: its path and current version, without its content. */
data class NoteEntry(val path: NotePath, val version: Version)

/** Content of a file (a note's UTF-8 text, or an attachment's bytes) together with the version it was read at. */
class VersionedContent(val bytes: ByteArray, val version: Version) {

    /** The content as UTF-8 text: what a note contains. */
    val text: String get() = String(bytes, Charsets.UTF_8)

    fun sameContentAs(other: VersionedContent): Boolean = bytes.contentEquals(other.bytes)

    override fun equals(other: Any?): Boolean =
        other is VersionedContent && version == other.version && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + version.hashCode()

    override fun toString(): String = "VersionedContent(${bytes.size} bytes, $version)"

    companion object {
        fun ofText(text: String, version: Version) = VersionedContent(text.toByteArray(Charsets.UTF_8), version)
    }
}
