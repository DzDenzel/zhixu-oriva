package com.planlock.plugin

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded daemon worker: slow in-process plugins cannot freeze the Compose caller. */
class PluginActionExecutor(private val timeoutMillis: Long = 2_000L) {
    private val quarantined = AtomicBoolean(false)
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "plugin-action").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun close() {
        worker.shutdownNow().forEach { (it as? java.util.concurrent.Future<*>)?.cancel(true) }
    }
    val isQuarantined: Boolean get() = quarantined.get()

    suspend fun execute(action: () -> PluginActionResult): PluginActionResult = withContext(Dispatchers.IO) {
        if (quarantined.get()) return@withContext PluginActionResult(PluginActionStatus.FAILED_OPEN, "此插件动作已暂停，重启应用后可重试")
        val future = try { worker.submit<PluginActionResult> { action() } }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            return@withContext PluginActionResult(PluginActionStatus.FAILED_OPEN, "操作繁忙，请稍后重试")
        }
        try { future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) {
            quarantined.set(true)
            future.cancel(true)
            close()
            PluginActionResult(PluginActionStatus.FAILED_OPEN, "操作超时，计划继续执行")
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            PluginActionResult(PluginActionStatus.FAILED_OPEN, "操作已中断，计划继续执行")
        } catch (_: java.util.concurrent.ExecutionException) {
            PluginActionResult(PluginActionStatus.FAILED_OPEN, "操作执行失败，计划继续执行")
        } catch (_: java.util.concurrent.CancellationException) {
            PluginActionResult(PluginActionStatus.FAILED_OPEN, "此插件动作已暂停，计划继续执行")
        }
    }
}
