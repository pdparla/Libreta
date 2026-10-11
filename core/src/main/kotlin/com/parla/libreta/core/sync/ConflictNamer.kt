package com.parla.libreta.core.sync

import com.parla.libreta.core.note.NotePath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Source of the current date, injectable so tests are deterministic. */
fun interface Today {

    /** Current date as `yyyy-MM-dd`. */
    fun isoDate(): String
}

object SystemToday : Today {
    // java.time needs API 26; SimpleDateFormat is available everywhere. A new instance per call because it is not thread-safe.
    override fun isoDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
}

/** Names the copy that keeps the local text when both sides changed a note: `Name (conflicto 2026-10-11).md`. */
class ConflictNamer(private val today: Today) {

    /** A free path next to [original]; adds ` 2`, ` 3`... while [isTaken] says the name is in use. */
    fun conflictPathFor(original: NotePath, isTaken: (NotePath) -> Boolean): NotePath {
        val stem = "${original.baseName} (conflicto ${today.isoDate()})"
        return generateSequence(1) { it + 1 }
            .map { n -> original.withBaseName(if (n == 1) stem else "$stem $n") }
            .first { !isTaken(it) }
    }
}
