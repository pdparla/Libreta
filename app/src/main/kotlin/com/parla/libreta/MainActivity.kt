package com.parla.libreta

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.parla.libreta.core.note.NoteNaming

/** Phase 1 placeholder: proves that Kotlin and the :core module run on the device. Replaced by the note list in phase 2. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = getString(
            R.string.smoke_status,
            KotlinVersion.CURRENT.toString(),
            Build.VERSION.SDK_INT,
            NoteNaming.titleOf("# Hola, Libreta"),
        )
        findViewById<TextView>(R.id.status).text = status
        Log.i(TAG, status)
    }

    private companion object {
        const val TAG = "Libreta"
    }
}
