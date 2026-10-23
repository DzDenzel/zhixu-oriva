package com.planlock.app.api

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SystemLocationState(val location: WeatherLocation? = null, val resolving: Boolean = false, val error: String? = null)

/** Weather and picking share one OS location provider; this layer never requests permission itself. */
class SystemLocationProvider private constructor(private val app: Context) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(SystemLocationState())
    val state = mutable.asStateFlow()
    private var resolvedAt = 0L
    suspend fun resolve(force: Boolean = false): WeatherLocation? = mutex.withLock {
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mutable.value = SystemLocationState(error="定位未授权，可手动选择")
            return@withLock null
        }
        val now = System.currentTimeMillis()
        if (!force && now-resolvedAt in 0..900_000 && mutable.value.location != null) return@withLock mutable.value.location
        mutable.value = mutable.value.copy(resolving=true,error=null)
        try {
            val location = DeviceWeatherLocation(app).resolve()
            resolvedAt = now
            mutable.value = SystemLocationState(location,error=if(location==null) "暂未获取当前位置，可手动选择" else null)
            location
        } finally { if(mutable.value.resolving) mutable.value=mutable.value.copy(resolving=false) }
    }
    companion object {
        @Volatile private var instance: SystemLocationProvider? = null
        fun get(context: Context): SystemLocationProvider = instance ?: synchronized(this) { instance ?: SystemLocationProvider(context.applicationContext).also { instance=it } }
    }
}
