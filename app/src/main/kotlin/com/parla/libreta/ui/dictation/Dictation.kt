package com.parla.libreta.ui.dictation

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.data.stt.SttNotConfiguredException
import com.parla.libreta.data.stt.SttUnauthorizedException
import com.parla.libreta.data.stt.WavRecorder
import com.parla.libreta.ui.SyncMessages
import okhttp3.Call
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Dictation (docs/07-ia.md, part 2): a tap starts recording, "Parar" sends the audio to the dictation service on
 * the server and the text comes back to [onText]. Recording stops by itself after [MAX_MILLIS]. Closing the dialog
 * or the screen throws the recording away.
 */
object Dictation {

    const val MAX_MILLIS = 5 * 60 * 1000L

    fun start(activity: Activity, onText: (String) -> Unit) {
        if (activity.container.sttSettings.load() == null) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.stt_title)
                .setMessage(R.string.stt_not_configured)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        Session(activity, onText).record()
    }

    private class Session(private val activity: Activity, private val onText: (String) -> Unit) {
        private val wav = File(activity.cacheDir, "dictation.wav")
        private val handler = Handler(Looper.getMainLooper())
        private lateinit var recorder: WavRecorder
        private lateinit var dialog: AlertDialog
        private var sending = false

        private val tick = object : Runnable {
            override fun run() {
                dialog.setMessage(activity.getString(R.string.stt_recording, time(recorder.elapsed), time(MAX_MILLIS)))
                handler.postDelayed(this, 500)
            }
        }

        fun record() {
            // At the limit: send first, so that closing the dialog does not throw the recording away.
            recorder = WavRecorder(wav, MAX_MILLIS) {
                handler.post {
                    if (dialog.isShowing) {
                        send()
                        dialog.dismiss()
                    }
                }
            }
            try {
                recorder.start()
            } catch (e: IOException) {
                Toast.makeText(activity, R.string.stt_no_microphone, Toast.LENGTH_LONG).show()
                return
            }
            dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.stt_title)
                .setMessage(activity.getString(R.string.stt_recording, time(0), time(MAX_MILLIS)))
                .setPositiveButton(R.string.stt_stop) { _, _ -> send() }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(DialogInterface.OnDismissListener {
                    handler.removeCallbacks(tick)
                    if (!sending) discard()
                })
                .create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
            handler.post(tick)
        }

        private fun discard() {
            Thread { recorder.stop(); wav.delete() }.start()
        }

        private fun send() {
            if (sending) return
            sending = true
            var call: Call? = null
            var cancelled = false
            @Suppress("DEPRECATION") // ProgressDialog is the progress dialog API 19 has.
            val progress = ProgressDialog(activity).apply {
                setMessage(activity.getString(R.string.stt_transcribing))
                setCancelable(true)
                setCanceledOnTouchOutside(false)
                setOnCancelListener { cancelled = true; call?.cancel() }
                show()
            }
            activity.container.sttBackground.execute({
                recorder.stop()
                try {
                    activity.container.stt.transcribe(wav) { call = it }
                } finally {
                    wav.delete()
                }
            }) { result ->
                if (activity.isDestroyed) return@execute
                progress.dismiss()
                if (cancelled) return@execute
                result
                    .onSuccess { text ->
                        if (text.isBlank()) Toast.makeText(activity, R.string.stt_nothing, Toast.LENGTH_LONG).show() else onText(text)
                    }
                    .onFailure { Toast.makeText(activity, activity.getString(R.string.stt_failed, describe(it)), Toast.LENGTH_LONG).show() }
            }
        }

        private fun describe(error: Throwable): String = when (error) {
            is SttUnauthorizedException -> activity.getString(R.string.stt_error_token)
            is SttNotConfiguredException -> activity.getString(R.string.stt_not_configured)
            else -> SyncMessages.describe(activity, error)
        }
    }

    private fun time(millis: Long): String {
        val seconds = millis / 1000
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
