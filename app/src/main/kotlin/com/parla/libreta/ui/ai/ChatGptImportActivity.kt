package com.parla.libreta.ui.ai

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.data.ai.ChatGptSession
import java.io.File
import java.io.IOException

/**
 * Receives the ChatGPT session that `scripts/chatgpt-login.sh` signed in on the PC (docs/07-ia.md): the script
 * pushes it to [INCOMING] with adb and starts this activity, which stores it in the app's private storage and
 * reports on logcat (tag [TAG]) so the script can delete the pushed copy.
 *
 * Exported so that `am start` can reach it. Any app could start it too, but only adb (the shell user) can write
 * in /data/local/tmp, so it can only ever import what the script pushed.
 */
class ChatGptImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = try {
            val session = ChatGptSession.fromJson(INCOMING.readText())
            container.chatGptSessions.save(session)
            Log.i(TAG, "session imported")
            getString(R.string.ai_import_ok, session.email)
        } catch (e: IOException) {
            Log.w(TAG, "session import failed: ${e.javaClass.simpleName}")
            getString(R.string.ai_import_failed)
        } catch (e: IllegalArgumentException) {
            // The message names the missing field, never a token.
            Log.w(TAG, "session import failed: ${e.message}")
            getString(R.string.ai_import_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val TAG = "LibretaAI"
        val INCOMING = File("/data/local/tmp/libreta-chatgpt.json")
    }
}
