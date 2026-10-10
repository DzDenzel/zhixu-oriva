package com.planlock.app.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.TodaySummary
import com.planlock.app.api.dto.DeviceDto
import com.planlock.app.api.AuthState
import com.planlock.app.api.TokenStore
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
    private fun key(userId: Long?) = stringPreferencesKey("sync_overview_${userId ?: "local"}")
    suspend fun read(userId: Long?): SyncOverview = preferences.data.first()[key(localOverviewOwner(userId, TokenStore.state.value))]
        ?.let { json.decodeFromString<SyncOverview>(it) } ?: SyncOverview()
    suspend fun save(userId: Long?, value: SyncOverview) {
        preferences.edit { it[key(userId)] = json.encodeToString(value) }
    }
}

internal fun localOverviewOwner(requestedUserId: Long?, state: AuthState): Long? =
    if (state.signedOut || state.ownershipMismatch) state.localDataUserId else requestedUserId
