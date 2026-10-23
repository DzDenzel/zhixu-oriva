package com.planlock.app.api

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import kotlin.coroutines.resume

data class WeatherLocation(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
)

data class WeatherSnapshot(
    val location: WeatherLocation,
    val temperatureC: Double,
    val weatherCode: Int,
    val observedAt: String,
    val fetchedAtMillis: Long,
) {
    fun matches(other: WeatherLocation): Boolean =
        location.latitude == other.latitude && location.longitude == other.longitude
}

object WeatherLocationChoice {
    fun select(located: WeatherLocation?, manual: WeatherLocation?): WeatherLocation? = manual ?: located
}

object WeatherLocationNaming {
    fun displayName(locality: String?, subAdminArea: String?, adminArea: String?, country: String?): String {
        val city = locality?.takeIf { it.isNotBlank() } ?: subAdminArea?.takeIf { it.isNotBlank() }
        return listOfNotNull(city, adminArea?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() })
            .distinct()
            .joinToString(" · ")
            .ifBlank { "当前位置" }
    }

    fun displayName(address: Address?): String = address?.let {
        displayName(it.locality, it.subAdminArea, it.adminArea, it.countryName)
    } ?: "当前位置"
}

class DeviceWeatherLocation(private val context: Context) {
    suspend fun resolve(): WeatherLocation? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val location = currentLocation() ?: return null
        val name = reverseName(location)
        return WeatherLocation(name, location.latitude, location.longitude, "auto")
    }

    // Permission is checked by resolve(); runCatching below also covers revocation races.
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val preferred = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.distinct().filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        val latestKnown = manager.allProviders.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull(Location::getTime)
        val provider = preferred.firstOrNull() ?: return latestKnown
        return withTimeoutOrNull(5_000) { requestCurrent(manager, provider) } ?: latestKnown
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrent(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }

                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                    .onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }

    private suspend fun reverseName(location: Location): String {
        if (!Geocoder.isPresent()) return "当前位置"
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = withTimeoutOrNull(5_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { continuation ->
                    runCatching {
                        geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        })
                    }.onFailure { if (continuation.isActive) continuation.resume(null) }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
        return WeatherLocationNaming.displayName(address)
    }
}

object WeatherParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLocations(payload: String): List<WeatherLocation> = runCatching {
        json.parseToJsonElement(payload).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val name = item.text("name") ?: return@mapNotNull null
            val latitude = item.number("latitude") ?: return@mapNotNull null
            val longitude = item.number("longitude") ?: return@mapNotNull null
            val timezone = item.text("timezone") ?: return@mapNotNull null
            val displayName = listOfNotNull(name, item.text("admin1"), item.text("country"))
                .distinct().joinToString(" · ")
            WeatherLocation(displayName, latitude, longitude, timezone)
        }
    }.getOrDefault(emptyList())

    fun parseCurrent(payload: String, location: WeatherLocation, fetchedAtMillis: Long): WeatherSnapshot? = runCatching {
        val current = json.parseToJsonElement(payload).jsonObject["current"]?.jsonObject ?: return null
        WeatherSnapshot(
            location = location,
            temperatureC = current.number("temperature_2m") ?: return null,
            weatherCode = current["weather_code"]?.jsonPrimitive?.intOrNull ?: return null,
            observedAt = current.text("time") ?: return null,
            fetchedAtMillis = fetchedAtMillis,
        )
    }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull
}

class WeatherClient {
    private val client = OkHttpClient.Builder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
    suspend fun searchCities(query: String): List<WeatherLocation> {
        if (query.isBlank()) return emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "6")
            .addQueryParameter("language", "zh")
            .addQueryParameter("format", "json")
            .build()
        return get(url.toString())?.let(WeatherParser::parseLocations).orEmpty()
    }

    suspend fun current(location: WeatherLocation, nowMillis: Long = System.currentTimeMillis()): WeatherSnapshot? {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code")
            .addQueryParameter("timezone", location.timezone)
            .addQueryParameter("forecast_days", "1")
            .build()
        return get(url.toString())?.let { WeatherParser.parseCurrent(it, location, nowMillis) }
    }

    private suspend fun get(url: String): String? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) { if (continuation.isActive) continuation.resume(null) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.use { runCatching { if (it.isSuccessful) it.body?.string() else null }.getOrNull() }
                if (continuation.isActive) continuation.resume(body)
            }
        })
    }
}

class WeatherStore(private val preferences: DataStore<Preferences>) {
    suspend fun location(): WeatherLocation? = preferences.data.first().toLocation()

    suspend fun saveLocation(location: WeatherLocation) {
        preferences.edit { values ->
            values[Keys.cityName] = location.displayName
            values[Keys.cityLat] = location.latitude.toString()
            values[Keys.cityLon] = location.longitude.toString()
            values[Keys.cityTimezone] = location.timezone
        }
    }

    suspend fun clearLocation() {
        preferences.edit { values ->
            values.remove(Keys.cityName)
            values.remove(Keys.cityLat)
            values.remove(Keys.cityLon)
            values.remove(Keys.cityTimezone)
            values.remove(Keys.cacheName)
            values.remove(Keys.cacheLat)
            values.remove(Keys.cacheLon)
            values.remove(Keys.cacheTimezone)
            values.remove(Keys.cacheTemp)
            values.remove(Keys.cacheCode)
            values.remove(Keys.cacheObserved)
            values.remove(Keys.cacheFetchedAt)
        }
    }

    suspend fun snapshot(location: WeatherLocation): WeatherSnapshot? {
        return preferences.data.first().toSnapshot()?.takeIf { it.matches(location) }
    }

    /** Disk only: entering a page must not resolve GPS or fetch the weather. */
    suspend fun cachedSnapshot(): WeatherSnapshot? {
        val values = preferences.data.first()
        val manual = values.toLocation()
        return values.toSnapshot()?.takeIf { manual == null || it.matches(manual) }
    }

    private fun Preferences.toSnapshot(): WeatherSnapshot? {
        return WeatherSnapshot(
            toCachedLocation() ?: return null,
            this[Keys.cacheTemp]?.toDoubleOrNull() ?: return null,
            this[Keys.cacheCode]?.toIntOrNull() ?: return null,
            this[Keys.cacheObserved] ?: return null,
            this[Keys.cacheFetchedAt] ?: return null,
        )
    }

    suspend fun saveSnapshot(snapshot: WeatherSnapshot) {
        preferences.edit { values ->
            values[Keys.cacheName] = snapshot.location.displayName
            values[Keys.cacheLat] = snapshot.location.latitude.toString()
            values[Keys.cacheLon] = snapshot.location.longitude.toString()
            values[Keys.cacheTimezone] = snapshot.location.timezone
            values[Keys.cacheTemp] = snapshot.temperatureC.toString()
            values[Keys.cacheCode] = snapshot.weatherCode.toString()
            values[Keys.cacheObserved] = snapshot.observedAt
            values[Keys.cacheFetchedAt] = snapshot.fetchedAtMillis
        }
    }

    private fun Preferences.toLocation(): WeatherLocation? {
        val name = this[Keys.cityName] ?: return null
        return WeatherLocation(
            name,
            this[Keys.cityLat]?.toDoubleOrNull() ?: return null,
            this[Keys.cityLon]?.toDoubleOrNull() ?: return null,
            this[Keys.cityTimezone] ?: return null,
        )
    }

    private fun Preferences.toCachedLocation(): WeatherLocation? {
        val name = this[Keys.cacheName] ?: return null
        return WeatherLocation(
            name,
            this[Keys.cacheLat]?.toDoubleOrNull() ?: return null,
            this[Keys.cacheLon]?.toDoubleOrNull() ?: return null,
            this[Keys.cacheTimezone] ?: return null,
        )
    }

    private object Keys {
        val cityName = stringPreferencesKey("weather_city_name")
        val cityLat = stringPreferencesKey("weather_city_lat")
        val cityLon = stringPreferencesKey("weather_city_lon")
        val cityTimezone = stringPreferencesKey("weather_city_timezone")
        val cacheName = stringPreferencesKey("weather_cache_name")
        val cacheLat = stringPreferencesKey("weather_cache_lat")
        val cacheLon = stringPreferencesKey("weather_cache_lon")
        val cacheTimezone = stringPreferencesKey("weather_cache_timezone")
        val cacheTemp = stringPreferencesKey("weather_cache_temp")
        val cacheCode = stringPreferencesKey("weather_cache_code")
        val cacheObserved = stringPreferencesKey("weather_cache_observed")
        val cacheFetchedAt = longPreferencesKey("weather_cache_fetched_at")
    }
}
