package com.planlock.app.api

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.coroutines.resume

data class PlaceSelection(val name: String, val latitude: Double, val longitude: Double) {
    init { require(validCoordinates(latitude, longitude)) { "地点坐标无效" } }
}

fun validCoordinates(latitude: Double?, longitude: Double?): Boolean =
    if (latitude == null || longitude == null) latitude == null && longitude == null
    else latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

/** Android geocoder first; city search fallback uses the existing Open-Meteo endpoint. */
class PlaceLookup(context: Context) {
    private val app = context.applicationContext
    suspend fun search(query: String): List<PlaceSelection> {
        if (query.isBlank()) return emptyList()
        val addresses = geocode(query = query.trim())
        if (addresses.isNotEmpty()) return addresses.map(::place)
        return WeatherClient().searchCities(query).map { PlaceSelection(it.displayName,it.latitude,it.longitude) }
    }
    suspend fun reverse(latitude: Double, longitude: Double): PlaceSelection? =
        geocode(latitude = latitude, longitude = longitude).firstOrNull()?.let(::place)

    private fun place(address: Address): PlaceSelection {
        val name = address.featureName?.takeUnless { it.isBlank() || it == address.subThoroughfare }
            ?: address.getAddressLine(0)?.takeIf { it.isNotBlank() }
            ?: WeatherLocationNaming.displayName(address)
        return PlaceSelection(name,address.latitude,address.longitude)
    }
    private suspend fun geocode(query: String? = null, latitude: Double = 0.0, longitude: Double = 0.0): List<Address> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(app, Locale.getDefault())
        return withTimeoutOrNull(5_000) {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
                val callback = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (continuation.isActive) continuation.resume(addresses) }
                    override fun onError(errorMessage: String?) { if (continuation.isActive) continuation.resume(emptyList()) }
                }
                runCatching {
                    if (query == null) geocoder.getFromLocation(latitude, longitude, 1, callback)
                    else geocoder.getFromLocationName(query, 6, callback)
                }.onFailure { if (continuation.isActive) continuation.resume(emptyList()) }
            } else runInterruptible(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { if (query == null) geocoder.getFromLocation(latitude,longitude,1).orEmpty() else geocoder.getFromLocationName(query,6).orEmpty() }.getOrDefault(emptyList())
            }
        }.orEmpty()
    }
}
