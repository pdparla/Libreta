package com.parla.libreta.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Runs work on [executor] and hands the result back on the main thread. A tiny stand-in for coroutines on API 19. */
class BackgroundRunner(private val executor: Executor) {

    private val mainThread = Handler(Looper.getMainLooper())

    fun <T> execute(work: () -> T, onDone: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(work)
            mainThread.post { onDone(result) }
        }
    }
}
