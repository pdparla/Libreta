package com.parla.libreta.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.data.ai.ChatGptSession
import com.parla.libreta.data.stt.SttSettings
import java.io.File
import java.io.IOException

/**
 * Receives what the PC scripts hand over with adb (docs/07-ia.md): the ChatGPT session from
 * `scripts/chatgpt-login.sh` and the dictation settings from `scripts/stt-pair.sh`. A script pushes its file to
 * /data/local/tmp and starts this activity, which stores whatever is there in the app's private storage and
 * reports on logcat (tag [TAG]) so the script can delete the pushed copy.
 *
 * Exported so that `am start` can reach it. Any app could start it too, but only adb (the shell user) can write
 * in /data/local/tmp, so it can only ever import what a script pushed.
 */
class AdbImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val messages = listOfNotNull(
            import(CHATGPT, "chatgpt session") { json ->
                val session = ChatGptSession.fromJson(json)
                container.chatGptSessions.save(session)
                getString(R.string.ai_import_ok, session.email)
            },
            import(STT, "stt settings") { json ->
                val settings = SttSettings.fromJson(json)
                container.sttSettings.save(settings)
                getString(R.string.stt_import_ok, settings.url)
            },
        )
        if (messages.isNotEmpty()) Toast.makeText(this, messages.joinToString("\n"), Toast.LENGTH_LONG).show()
        finish()
    }

    /** Imports [file] if it is there with [store]; null if there is nothing to import. */
    private fun import(file: File, what: String, store: (String) -> String): String? {
        if (!file.exists()) return null
        return try {
            store(file.readText()).also { Log.i(TAG, "$what imported") }
        } catch (e: IOException) {
            Log.w(TAG, "$what import failed: ${e.javaClass.simpleName}")
            getString(R.string.import_failed)
        } catch (e: IllegalArgumentException) {
            // The message names the missing field, never a secret.
            Log.w(TAG, "$what import failed: ${e.message}")
            getString(R.string.import_failed)
        }
    }

    companion object {
        const val TAG = "LibretaImport"
        val CHATGPT = File("/data/local/tmp/libreta-chatgpt.json")
        val STT = File("/data/local/tmp/libreta-stt.json")
    }
}
