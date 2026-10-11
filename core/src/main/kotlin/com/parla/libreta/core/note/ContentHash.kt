package com.parla.libreta.core.note

import java.security.MessageDigest

/** Content-derived [Version] for stores without native versions, such as local files. */
object ContentHash {

    private const val HEX = "0123456789abcdef"

    fun of(content: ByteArray): Version {
        val digest = MessageDigest.getInstance("SHA-1").digest(content)
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val b = byte.toInt() and 0xff
            hex.append(HEX[b ushr 4]).append(HEX[b and 0x0f])
        }
        return Version("sha1:$hex")
    }

    fun of(text: String): Version = of(text.toByteArray(Charsets.UTF_8))
}
