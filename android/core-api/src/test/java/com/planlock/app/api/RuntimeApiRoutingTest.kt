package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeApiRoutingTest {
    @Test fun existingFacadeFollowsSwitchAndRejectsInFlightOldResponse() = runBlocking {
        val p = MutableStateFlow(emptyPreferences())
        val store = object : DataStore<Preferences> {
            override val data = p
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(p.value).also { p.value = it }
        }
        TokenStore.restore(store)
        val api = ApiClient.create(false)
        val workers = Executors.newCachedThreadPool()
        try {
            ServerSocket(0).use { a -> ServerSocket(0).use { b ->
                fun config(server: ServerSocket) = ConnectionConfig(1, "测试", "http://127.0.0.1:${server.localPort}/api/v1/", "http://127.0.0.1:${server.localPort}/catalog/", true)
                fun serve(server: ServerSocket, arrived: CountDownLatch? = null, release: CountDownLatch? = null) = workers.submit<String> {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        arrived?.countDown(); release?.await(5, TimeUnit.SECONDS)
                        val body = """{"code":0,"message":"ok","data":[]}"""
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                        request
                    }
                }
                TokenStore.changeConnection(store, config(a))
                val first = serve(a)
                assertEquals(0, api.plans().code)
                assertEquals("GET /api/v1/plans HTTP/1.1", first.get(5, TimeUnit.SECONDS))
                val arrived = CountDownLatch(1); val release = CountDownLatch(1)
                val old = serve(a, arrived, release)
                val pending = async(Dispatchers.IO) { runCatching { api.plans() } }
                assertTrue(arrived.await(5, TimeUnit.SECONDS))
                TokenStore.changeConnection(store, config(b)); release.countDown()
                assertTrue("Old response crossed connection revision", pending.await().isFailure)
                old.get(5, TimeUnit.SECONDS)
                val second = serve(b)
                assertEquals(0, api.plans().code)
                assertEquals("GET /api/v1/plans HTTP/1.1", second.get(5, TimeUnit.SECONDS))
            } }
        } finally {
            workers.shutdownNow(); p.value = emptyPreferences(); TokenStore.restore(store)
        }
        Unit
    }
}
