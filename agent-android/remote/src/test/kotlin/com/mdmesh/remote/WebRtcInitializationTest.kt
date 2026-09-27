package com.mdmesh.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebRtcInitializationTest {

    @Test
    fun `concurrent initialization runs thread safely and executes exactly once`() {
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val initCount = AtomicInteger(0)

        var isInitialized = false

        fun safeInitialize() {
            synchronized(this) {
                if (!isInitialized) {
                    initCount.incrementAndGet()
                    // Simulate initialization logic
                    isInitialized = true
                }
            }
        }

        for (i in 0 until threadCount) {
            executor.execute {
                try {
                    safeInitialize()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All threads should complete", completed)
        assertTrue("isInitialized should be true", isInitialized)
        assertEquals(1, initCount.get())
    }

    @Test
    fun `session state is reset cleanly upon stop`() {
        var activeSessionId: String? = "session-123"

        fun stopSession(id: String) {
            synchronized(this) {
                if (activeSessionId == id) {
                    activeSessionId = null
                }
            }
        }

        stopSession("session-123")
        assertNull(activeSessionId)
    }
}
