package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectionHttpTest {
    @Test fun drippingResponseStillHasTotalDeadlineAndCancellationClosesSocket() {
        listOf(false, true).forEach { cancel ->
            ServerSocket(0).use { server ->
                val workers = Executors.newSingleThreadExecutor()
                val entered = java.util.concurrent.CountDownLatch(1)
                val closed = java.util.concurrent.CountDownLatch(1)
                val writer = workers.submit {
                    server.accept().use { socket ->
                        try {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                            entered.countDown()
                            repeat(60) { socket.getOutputStream().write(65); socket.getOutputStream().flush(); Thread.sleep(50) }
                        } catch (_: Exception) { closed.countDown() }
                    }
                }
                val config = ConnectionConfig(1, "滴流", "http://127.0.0.1:${server.localPort}/", "http://127.0.0.1:${server.localPort}/", true)
                val client = ConnectionHttp.client(config).newBuilder().callTimeout(250, TimeUnit.MILLISECONDS).build()
                val call = client.newCall(Request.Builder().url(config.apiBaseUrl).build())
                val done = java.util.concurrent.CountDownLatch(1)
                val start = System.nanoTime()
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { done.countDown() }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close(); fail("Incomplete response accepted") }
                })
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (cancel) call.cancel()
                assertTrue("LAN call ignored total deadline/cancel", done.await(1_500, TimeUnit.MILLISECONDS))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
                assertTrue("Underlying LAN socket remained open", closed.await(1, TimeUnit.SECONDS))
                writer.cancel(true); workers.shutdownNow()
            }
        }
    }
    @Test fun selectedLanEndpointWorksAndPluginRequestsHaveNoBusinessToken() = runBlocking {
        val prefs = MutableStateFlow(emptyPreferences())
        val store = object : DataStore<Preferences> {
            override val data = prefs
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(prefs.value).also { prefs.value = it }
        }
        ConnectionStore.restore(store); TokenStore.restore(store)
        ServerSocket(0).use { server ->
            val config = ConnectionConfig(1, "夹具", "http://127.0.0.1:${server.localPort}/api/v1/", "http://127.0.0.1:${server.localPort}/plugins/catalog/", true)
            TokenStore.changeConnection(store, config)
            TokenStore.save(store, AuthSession("BUSINESS_TOKEN", 7, 8))
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit<String> {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = buildList { while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; add(line) } }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: text/plain\r\n\r\n5\r\nhello\r\n0\r\n\r\n".toByteArray())
                        headers.joinToString("\n")
                    }
                }
                assertEquals("hello", ConnectionHttp.publicGet(config.catalogUrl("official")).decodeToString())
                assertFalse(future.get(5, TimeUnit.SECONDS).contains("BUSINESS_TOKEN"))
                try { ConnectionHttp.publicGet("http://127.0.0.1:1/wrong-origin"); fail("Unconfigured HTTP destination accepted") }
                catch (_: java.io.IOException) { }
            } finally { worker.shutdownNow() }
        }
        TokenStore.changeConnection(store, null)
        try { ApiClient.create(false).plans(); fail("Local-only performed API request") } catch (_: java.io.IOException) { }
        prefs.value = emptyPreferences(); ConnectionStore.restore(store); TokenStore.restore(store); Unit
    }
}
