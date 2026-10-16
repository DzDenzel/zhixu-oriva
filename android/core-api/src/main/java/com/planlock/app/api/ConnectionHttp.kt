package com.planlock.app.api

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** HTTPS uses platform trust. Only explicitly configured private HTTP endpoints use the LAN path. */
object ConnectionHttp {
    internal fun client(config: ConnectionConfig, publicOnly: Boolean = false): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.scheme == "http") lanExchange(request, config, publicOnly, chain.call())
            else chain.proceed(request)
        }.build()

    /** Plugin transport deliberately has no access to TokenStore and no authentication interceptor. */
    fun publicGet(url: String): ByteArray {
        val config = ConnectionStore.state.value.config ?: ConnectionConfig.default
        val request = Request.Builder().url(url).header("Accept", "application/json, application/octet-stream").build()
        if (request.url.username.isNotEmpty() || request.url.password.isNotEmpty()) throw IOException("插件地址不得包含登录凭据")
        return client(config, publicOnly = true).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("插件请求失败：HTTP ${response.code}")
            response.body?.byteStream()?.use { readBounded(it, MAX_BYTES) } ?: throw IOException("插件响应为空")
        }
    }

    private const val MAX_BYTES = 32 * 1024 * 1024

    private fun lanExchange(request: Request, config: ConnectionConfig, publicOnly: Boolean, call: Call): Response {
        val approved = (if (publicOnly) config.pluginsCatalogUrl else config.apiBaseUrl).toHttpUrl()
        val target = request.url
        if (!config.lanAllowed || !sameOrigin(approved, target) || !isLanHost(target.host)) throw IOException("未允许此内网连接")
        // Connect to the exact address that passed validation; no second DNS lookup or redirect.
        Socket().use { socket ->
            LanCallDeadline(socket, call).use { deadline ->
                val addresses = deadline.resolve(target.host)
                if (addresses.isEmpty() || addresses.any { !isLanAddress(it) }) throw IOException("内网地址解析到了外部网络")
                if (call.isCanceled()) throw IOException("请求已取消")
                socket.soTimeout = 15_000
                socket.connect(InetSocketAddress(addresses.first(), target.port), 10_000)
                val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                if (body.size > MAX_BYTES) throw IOException("请求过大")
                val path = target.encodedPath + (target.encodedQuery?.let { "?$it" } ?: "")
                val host = (if (target.host.contains(':')) "[${target.host}]" else target.host) + ":${target.port}"
                val head = buildString {
                    append("${request.method} $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nAccept-Encoding: identity\r\n")
                    request.headers.forEach { (name, value) ->
                        if (name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding", "accept-encoding")) append("$name: $value\r\n")
                    }
                    request.body?.contentType()?.let { if (request.header("Content-Type") == null) append("Content-Type: $it\r\n") }
                    if (request.body != null) append("Content-Length: ${body.size}\r\n")
                    append("\r\n")
                }
                socket.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
                val input = socket.getInputStream().buffered()
                val status = line(input).split(' ', limit = 3)
                if (status.size < 2 || status[0] !in setOf("HTTP/1.1", "HTTP/1.0")) throw IOException("无效的内网响应")
                val code = status[1].toIntOrNull()?.takeIf { it in 200..599 } ?: throw IOException("无效的内网响应状态")
                val headers = Headers.Builder()
                var headerBytes = 0
                while (true) {
                    val value = line(input)
                    headerBytes += value.length + 2
                    if (headerBytes > 65_536) throw IOException("响应头过大")
                    if (value.isEmpty()) break
                    val colon = value.indexOf(':')
                    if (colon <= 0) throw IOException("无效的内网响应头")
                    headers.add(value.substring(0, colon), value.substring(colon + 1).trim())
                }
                val resultHeaders = headers.build()
                val lengths = resultHeaders.values("Content-Length")
                val transfer = resultHeaders["Transfer-Encoding"]
                if (lengths.size > 1 || transfer != null && lengths.isNotEmpty()) throw IOException("响应长度不明确")
                val bytes = when {
                    request.method == "HEAD" || code == 204 || code == 304 -> byteArrayOf()
                    transfer?.equals("chunked", true) == true -> chunks(input)
                    transfer != null -> throw IOException("不支持的内网传输编码")
                    lengths.isNotEmpty() -> exact(input, lengths.single().toLongOrNull() ?: -1)
                    else -> readBounded(input, MAX_BYTES)
                }
                if (call.isCanceled()) throw IOException("请求已取消")
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message(status.getOrElse(2) { "" }).headers(resultHeaders)
                    .body(bytes.toResponseBody(resultHeaders["Content-Type"]?.toMediaTypeOrNull())).build()
            }
        }
    }

    private fun line(input: InputStream): String {
        val output = ByteArrayOutputStream()
        while (output.size() <= 8_192) {
            val b = input.read()
            if (b < 0) throw EOFException("内网响应意外结束")
            if (b == 10) {
                val bytes = output.toByteArray()
                if (bytes.isEmpty() || bytes.last() != 13.toByte()) throw IOException("响应换行无效")
                return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
            }
            output.write(b)
        }
        throw IOException("响应行过长")
    }

    private fun exact(input: InputStream, size: Long): ByteArray {
        if (size !in 0..MAX_BYTES.toLong()) throw IOException("响应过大或长度无效")
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw EOFException("内网响应正文不完整")
            offset += count
        }
        return bytes
    }

    private fun chunks(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val size = line(input).substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("分块长度无效")
            if (size == 0L) {
                var trailerBytes = 0
                while (true) { val trailer = line(input); trailerBytes += trailer.length + 2; if (trailerBytes > 65_536) throw IOException("响应尾部过大"); if (trailer.isEmpty()) break }
                return output.toByteArray()
            }
            if (size < 0 || size > MAX_BYTES - output.size()) throw IOException("响应过大")
            output.write(exact(input, size))
            if (line(input).isNotEmpty()) throw IOException("分块结束标记无效")
        }
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size().toLong() + count > maximum) throw IOException("响应过大")
            output.write(buffer, 0, count)
        }
    }
}

internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
