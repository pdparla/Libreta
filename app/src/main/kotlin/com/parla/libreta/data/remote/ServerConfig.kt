package com.parla.libreta.data.remote

import okhttp3.HttpUrl

/**
 * Where and how to reach the notes on Nextcloud. The app password is stored only on the tablet (see SettingsStore).
 *
 * @param certificateName name the server certificate is issued to, when it differs from the host in [serverUrl]
 *   (here: the server is reached by IP and its certificate says `nextcloud.local`). Blank means the standard
 *   host name check.
 */
data class ServerConfig(
    val serverUrl: String,
    val user: String,
    val appPassword: String,
    val notesFolder: String = DEFAULT_NOTES_FOLDER,
    val certificateName: String = "",
) {
    val isComplete: Boolean get() = notesUrl() != null && appPassword.isNotEmpty()

    /** Identifies which set of remote notes this is. When it changes, the stored sync state no longer applies. */
    val accountKey: String get() = "${notesUrl()}"

    /** WebDAV URL of the notes folder, or null if the URL is not HTTPS or the user is missing. */
    fun notesUrl(): HttpUrl? {
        val base = HttpUrl.parse(serverUrl.trim()) ?: return null
        if (!base.isHttps || user.isBlank()) return null
        val builder = base.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(user.trim())
        notesFolder.split('/').map { it.trim() }.filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        return builder.addPathSegment("").build()
    }

    /** Never prints the password. */
    override fun toString(): String =
        "ServerConfig(serverUrl=$serverUrl, user=$user, notesFolder=$notesFolder, certificateName=$certificateName)"

    companion object {
        const val DEFAULT_NOTES_FOLDER = "Notes"
    }
}
