package com.planlock.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DomesticPlace(val name: String, val address: String?, val latitude: Double, val longitude: Double)

/** Public map transport: never inherits backend authentication, cookies or logging interceptors. */
class TiandituPlaceClient(private val key: String) {
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun search(query: String, near: PlaceSelection?, radius: Int? = null): List<DomesticPlace> {
        val document = searchDocument(query, near, radius)
        return parsePois(get("https://api.tianditu.gov.cn/v2/search", "postStr", document.toString(), "query"))
    }

    suspend fun reverse(latitude: Double, longitude: Double): DomesticPlace? {
        val document = buildJsonObject { put("lon", longitude); put("lat", latitude); put("ver", 1) }
        return parseReverse(get("https://api.tianditu.gov.cn/geocoder", "postStr", document.toString(), "geocode"), latitude, longitude)
    }

    private suspend fun get(endpoint: String, parameter: String, value: String, type: String): String {
        awaitRequestSlot()
        return runInterruptible(Dispatchers.IO) {
            val url = endpoint.toHttpUrl().newBuilder().addQueryParameter(parameter, value).addQueryParameter("type", type).addQueryParameter("tk", key).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("地图检索暂不可用")
                val body = response.body ?: throw IOException("地图检索无响应")
                body.source().let { source ->
                    if (source.request(1_048_577) && source.buffer.size > 1_048_576) throw IOException("地图响应过大")
                    source.readUtf8()
                }
            }
        }
    }

    companion object {
        // All picker/field instances share the start-rate limit; waiting remains cancellable.
        private val requestGate = Mutex()
        private var nextRequestNanos = 0L
        private suspend fun awaitRequestSlot() = requestGate.withLock {
            val waitNanos = nextRequestNanos - System.nanoTime()
            if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
            nextRequestNanos = System.nanoTime() + 550_000_000L
        }

        internal fun searchDocument(query: String, near: PlaceSelection?, radius: Int? = null): JsonObject {
            val keyword = query.trim().let { if (it.equals("kfc", ignoreCase = true)) "肯德基" else it }
            require(keyword.isNotBlank()) { "地点检索需要关键词" }
            return buildJsonObject {
                put("keyWord", keyword.take(100)); put("level", 16); put("start", 0); put("count", 30)
                put("queryType", if (near == null) 1 else 3)
                if (near != null) {
                    put("pointLonlat", "${near.longitude},${near.latitude}")
                    put("queryRadius", (radius ?: 10_000).coerceIn(1, 10_000))
                } else put("mapBound", "73,18,135,54")
            }
        }

        internal fun parsePois(raw: String): List<DomesticPlace> {
            val doc = Json.parseToJsonElement(raw).jsonObject
            val status = (doc["status"] as? JsonObject)?.get("infocode")?.jsonPrimitive?.intOrNull
            if (status != null && status !in setOf(1000, 3001)) throw IOException("地图检索暂不可用，请检查地图配置")
            return (doc["pois"] as? JsonArray).orEmpty().take(100).mapNotNull { item ->
                val value = item as? JsonObject ?: return@mapNotNull null
                val name = value["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val coordinates = value["lonlat"]?.jsonPrimitive?.contentOrNull?.split(',') ?: return@mapNotNull null
                val lon = coordinates.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = coordinates.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                if (!validCoordinates(lat, lon)) return@mapNotNull null
                DomesticPlace(name.take(200), value["address"]?.jsonPrimitive?.contentOrNull?.take(300), lat, lon)
            }.distinctBy { Triple(it.name, it.latitude, it.longitude) }
        }
        internal fun parseReverse(raw: String, lat: Double, lon: Double): DomesticPlace? {
            val result = Json.parseToJsonElement(raw).jsonObject["result"] as? JsonObject ?: return null
            val address = result["formatted_address"]?.jsonPrimitive?.contentOrNull ?: return null
            val poi = (result["addressComponent"] as? JsonObject)?.get("poi")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            return DomesticPlace((poi ?: address).take(200), address.take(300), lat, lon)
        }
    }
}
