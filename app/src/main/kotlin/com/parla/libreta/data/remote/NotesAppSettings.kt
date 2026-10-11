package com.parla.libreta.data.remote

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Reads the settings of the Nextcloud Notes app to learn which folder it keeps notes in. The folder is
 * configurable there (here it is "Notas", not the default "Notes"), and syncing any other folder would
 * keep the notes out of the Notes app.
 */
class NotesAppSettings(private val client: OkHttpClient, private val serverUrl: HttpUrl) {

    /** The Notes app's folder, or null if the app is not installed or the server cannot be asked. */
    fun notesPath(): String? {
        val url = serverUrl.newBuilder().addPathSegments("index.php/apps/notes/api/v1/settings").build()
        val request = Request.Builder()
            .url(url)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) parseNotesPath(response.body()!!.string()) else null
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        // A regex instead of org.json, which is only a stub in JVM unit tests. The value is a plain folder name.
        private val NOTES_PATH = Regex(""""notesPath"\s*:\s*"((?:\\.|[^"\\])*)"""")

        internal fun parseNotesPath(json: String): String? =
            NOTES_PATH.find(json)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?.trim('/', ' ')
                ?.takeIf { it.isNotEmpty() }
    }
}
