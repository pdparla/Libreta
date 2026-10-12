package com.parla.libreta.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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

/**
 * A single-thread executor that runs tasks one at a time in submission order, like
 * `Executors.newSingleThreadExecutor()`, but whose thread ends after 30 s without work and is created again on
 * demand, so an idle screen does not keep a thread (and its stack) alive per executor.
 */
fun idleSingleThreadExecutor(threadName: String): Executor =
    ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { task -> Thread(task, threadName) }
        .apply { allowCoreThreadTimeOut(true) }
