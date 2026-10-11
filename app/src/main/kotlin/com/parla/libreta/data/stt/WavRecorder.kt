package com.parla.libreta.data.stt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records the microphone to [file] as a WAV of 16-bit mono PCM at 16 kHz, what Whisper works with: about 1.9 MB a
 * minute. Recording runs on its own thread and stops by itself after [maxMillis].
 */
class WavRecorder(private val file: File, private val maxMillis: Long, private val onLimit: () -> Unit) {

    @Volatile private var recording = false
    private var thread: Thread? = null
    private var startedAt = 0L

    /** Milliseconds recorded so far. */
    val elapsed: Long get() = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt

    /** @throws IOException if the microphone cannot be opened. */
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IOException("16 kHz mono recording not supported")
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE) // at least half a second
        // RECORD_AUDIO is granted at install on Android 4.4; on newer Android it could be missing.
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (e: SecurityException) {
            throw IOException("No permission to record", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("Microphone not available")
        }
        val out = RandomAccessFile(file, "rw")
        out.setLength(0)
        out.write(ByteArray(HEADER_SIZE)) // filled in when the size is known
        record.startRecording()
        recording = true
        startedAt = SystemClock.elapsedRealtime()
        thread = Thread({ capture(record, out, bufferSize) }, "wav-recorder").also { it.start() }
    }

    /** Stops and completes the file. Safe to call more than once. */
    fun stop() {
        recording = false
        thread?.join()
        thread = null
    }

    private fun capture(record: AudioRecord, out: RandomAccessFile, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        var dataBytes = 0L
        var hitLimit = false
        try {
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                    dataBytes += read
                }
                if (elapsed >= maxMillis) {
                    hitLimit = true
                    break
                }
            }
        } finally {
            record.stop()
            record.release()
            out.seek(0)
            out.write(header(dataBytes))
            out.close()
        }
        if (hitLimit) {
            recording = false
            onLimit()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val HEADER_SIZE = 44

        /** The 44-byte header of a PCM WAV holding [dataBytes] of 16-bit mono samples at [SAMPLE_RATE]. */
        fun header(dataBytes: Long): ByteArray {
            val byteRate = SAMPLE_RATE * 2
            val h = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(byteRate).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(dataBytes.toInt())
            return h.array()
        }
    }
}
