package com.planlock.app.api

import okhttp3.Call
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.*

/** Raw LAN sockets are outside OkHttp's connection pool: explicitly bind deadline and cancellation. */
internal class LanCallDeadline(private val socket: Socket, private val call: Call) : Closeable {
    private val end = System.nanoTime() + (call.timeout().timeoutNanos().takeIf { it > 0 } ?: TimeUnit.SECONDS.toNanos(30)).coerceAtMost(TimeUnit.SECONDS.toNanos(30))
    private val watcher = timer.scheduleAtFixedRate({
        if (call.isCanceled() || System.nanoTime() >= end) runCatching { socket.close() }
    }, 0, 25, TimeUnit.MILLISECONDS)

    fun resolve(host: String): Array<InetAddress> {
        val future = try { dns.submit(Callable { InetAddress.getAllByName(host) }) }
        catch (_: RejectedExecutionException) { throw IOException("内网地址解析繁忙，请稍后重试") }
        try {
            while (true) {
                val remaining = end - System.nanoTime()
                if (call.isCanceled() || remaining <= 0) throw IOException("内网请求已取消或超时")
                try { return future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS) }
                catch (_: TimeoutException) { /* Recheck cancellation and absolute deadline. */ }
            }
        } catch (e: ExecutionException) { throw IOException("内网地址解析失败", e.cause) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("内网地址解析已中断") }
        finally { future.cancel(true) }
    }

    override fun close() { watcher.cancel(false) }

    companion object {
        private val threads = ThreadFactory { task -> Thread(task, "oriva-lan-deadline").apply { isDaemon = true } }
        private val timer = ScheduledThreadPoolExecutor(1, threads).apply { removeOnCancelPolicy = true }
        // Native DNS may ignore interruption; bound both its workers and waiting queue.
        private val dns = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8), threads).apply { allowCoreThreadTimeOut(true) }
    }
}
