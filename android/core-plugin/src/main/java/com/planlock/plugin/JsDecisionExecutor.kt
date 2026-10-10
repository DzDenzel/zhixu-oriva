package com.planlock.plugin

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Native JS never blocks Accessibility's main caller; a busy worker rejects excess input. */
class JsDecisionExecutor {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "plugin-js-decision").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun submit(action: () -> Unit): Boolean = try {
        worker.execute { runCatching(action) }; true
    } catch (_: RejectedExecutionException) { false }
    fun close() { worker.shutdownNow() }
}
