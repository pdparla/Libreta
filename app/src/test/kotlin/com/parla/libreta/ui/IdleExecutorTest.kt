package com.parla.libreta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class IdleExecutorTest {

    @Test
    fun `runs tasks in order on one named thread`() {
        val executor = idleSingleThreadExecutor("test-idle") as ThreadPoolExecutor
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        val done = CountDownLatch(20)
        repeat(20) { i ->
            executor.execute {
                order.add(i)
                threads.add(Thread.currentThread().name)
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals((0 until 20).toList(), order.toList())
        assertEquals(setOf("test-idle"), threads.toSet())
        executor.shutdown()
    }

    @Test
    fun `lets the thread die when idle`() {
        val executor = idleSingleThreadExecutor("test-idle") as ThreadPoolExecutor
        assertTrue(executor.allowsCoreThreadTimeOut())
        assertEquals(30L, executor.getKeepAliveTime(TimeUnit.SECONDS))
        assertEquals(1, executor.maximumPoolSize)
        executor.shutdown()
    }
}
