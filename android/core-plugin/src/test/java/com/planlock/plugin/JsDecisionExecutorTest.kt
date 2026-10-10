package com.planlock.plugin

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class JsDecisionExecutorTest {
    @Test fun invocationReturnsWhilePluginWorkIsStillBlockedAndQueueIsBounded() {
        val executor = JsDecisionExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(executor.submit { entered.countDown(); release.await(5, TimeUnit.SECONDS) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(executor.submit {})
            assertFalse("Unbounded input must not accumulate", executor.submit {})
        } finally { release.countDown(); executor.close() }
    }
    @Test fun closedWorkerRejectsFutureCallbacks() {
        val executor = JsDecisionExecutor()
        executor.close()
        assertFalse(executor.submit { fail("Closed executor ran callback") })
    }
}
