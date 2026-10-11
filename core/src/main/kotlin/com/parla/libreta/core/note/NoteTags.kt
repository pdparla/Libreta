package com.parla.libreta.core.note

/**
 * Tags in a note's Markdown (docs/13-indice.md): words right after a `#`, like `#compras` or `#viaje-2026`, in the
 * managed "Etiquetas:" line of a handwritten note or anywhere in a text note. A `#` followed by a space is a heading
 * (`# Título`), and one inside a word or a link (`nota.md#2`) is not a tag.
 */
object NoteTags {

    private val TAG = Regex("""(?<![\p{L}\p{N}_#&/\-.])#([\p{L}\p{N}][\p{L}\p{N}_\-]*)""")

    /** The tags in [markdown], lower case, without `#` and without repeats. */
    fun of(markdown: String): List<String> = TAG.findAll(markdown).map { it.groupValues[1].lowercase() }.distinct().toList()
}
