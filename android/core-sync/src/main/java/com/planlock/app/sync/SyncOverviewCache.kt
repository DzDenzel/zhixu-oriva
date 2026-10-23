package com.planlock.app.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.TodaySummary
import com.planlock.app.api.dto.DeviceDto
import com.planlock.app.api.AuthState
import com.planlock.app.api.TokenStore
import com.planlock.app.api.ConnectionStore
import com.planlock.app.api.ConnectionConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class SyncOverview(
    val today: TodaySummary? = null,
    val stats: StatsResponse? = null,
    val statsByPeriod: Map<String, StatsResponse> = emptyMap(),
    val deviceCount: Int? = null,
    val devices: List<DeviceDto>? = null,
    val updatedAtEpochMs: Long? = null,
) {
    fun stats(period: String): StatsResponse? = statsByPeriod[period] ?: stats.takeIf { period == "week" }
}

internal fun SyncOverview.withReportScopes(
    reports: Map<String, StatsResponse>,
    refreshedAtEpochMs: Long,
): SyncOverview = copy(
    stats = reports.getValue("week"),
    statsByPeriod = reports,
    updatedAtEpochMs = refreshedAtEpochMs,
)

/** User-scoped persistent cache; a missing cache is not an I/O failure. */
class SyncOverviewCache(private val preferences: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(instance: String?, userId: Long?): Preferences.Key<String> {
        val scope = java.security.MessageDigest.getInstance("SHA-256").digest((instance ?: "local").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("sync_overview_${scope}_${userId ?: "local"}")
    }
    suspend fun read(userId: Long?): SyncOverview {
        val state = TokenStore.state.value
        val owner = localOverviewOwner(userId, state)
        val instance = ConnectionStore.state.value.instanceId
        val p = preferences.data.first()
        val raw = p[key(instance, owner)] ?: if (instance == ConnectionConfig.default.instanceId) p[stringPreferencesKey("sync_overview_${owner ?: "local"}")] else null
        return raw?.let { json.decodeFromString<SyncOverview>(it) } ?: SyncOverview()
    }
    suspend fun readScope(scope: String): SyncOverview {
        val instance = scope.substringBeforeLast('\n').takeUnless { scope == "local\nlocal" }
        val owner = scope.substringAfterLast('\n').toLongOrNull()
        val p = preferences.data.first()
        val raw = p[key(instance, owner)] ?: if (instance == ConnectionConfig.default.instanceId) p[stringPreferencesKey("sync_overview_${owner ?: "local"}")] else null
        return raw?.let { json.decodeFromString<SyncOverview>(it) } ?: SyncOverview()
    }
    suspend fun save(userId: Long?, value: SyncOverview) {
        val instance = ConnectionStore.state.value.instanceId
        preferences.edit { it[key(instance, userId)] = json.encodeToString(value) }
    }
}

internal fun localOverviewOwner(requestedUserId: Long?, state: AuthState): Long? =
    if (state.signedOut) null else state.session.userId ?: requestedUserId
