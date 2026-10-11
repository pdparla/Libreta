package com.parla.libreta.data.local

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces [target] with [text] so that readers see either the old or the new content, never a half-written
 * file: writes a hidden sibling, syncs it to disk and renames it over the target (rename(2) is atomic on ext4).
 */
internal fun writeAtomically(target: File, text: String) {
    val dir = target.parentFile ?: throw IOException("No parent folder for $target")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
    val temp = File(dir, ".${target.name}.tmp")
    try {
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temp.renameTo(target)) throw IOException("Cannot replace $target")
    } finally {
        temp.delete()
    }
}
