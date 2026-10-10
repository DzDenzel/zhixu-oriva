package com.planlock.plugin

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginActionExecutorTest {
    @Test fun nonCooperativePluginCannotPoisonAnotherPluginAndIsQuarantined() = runBlocking {
        val keepRunning = AtomicBoolean(true)
        val bad = PluginActionExecutor(100)
        val other = PluginActionExecutor(500)
        try {
            val timedOut = bad.execute {
                while (keepRunning.get()) {
                    Thread.interrupted()
                    LockSupport.parkNanos(1_000_000)
                }
                PluginActionResult(PluginActionStatus.EXECUTED)
            }
            assertEquals(PluginActionStatus.FAILED_OPEN, timedOut.status)
            assertEquals(PluginActionStatus.EXECUTED, other.execute { PluginActionResult(PluginActionStatus.EXECUTED) }.status)
            var calledAgain = false
            assertEquals(PluginActionStatus.FAILED_OPEN, bad.execute { calledAgain = true; PluginActionResult(PluginActionStatus.EXECUTED) }.status)
            assertFalse(calledAgain)
        } finally { keepRunning.set(false) }
    }
    @Test fun normalActionRunsOffCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val result = PluginActionExecutor().execute {
            assertNotEquals(caller, Thread.currentThread())
            PluginActionResult(PluginActionStatus.EXECUTED, "done")
        }
        assertEquals(PluginActionStatus.EXECUTED, result.status)
        assertEquals("done", result.message)
    }
    @Test fun slowActionHasBoundedWaitAndVisibleFailure() = runBlocking {
        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)
        val start = System.nanoTime()
        val result = PluginActionExecutor(100).execute {
            entered.countDown()
            never.await()
            PluginActionResult(PluginActionStatus.EXECUTED)
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(PluginActionStatus.FAILED_OPEN, result.status)
        assertFalse(result.message.isNullOrBlank())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
    }
}
