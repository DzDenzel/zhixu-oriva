package com.planlock.app.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.planlock.app.api.dto.StatsResponse
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SyncOverviewCacheTest {
    @Test fun sameUserIdOnDifferentInstancesKeepsSeparateSnapshots() = runBlocking {
        val disk = MemoryStore()
        com.planlock.app.api.TokenStore.restore(disk)
        val original = com.planlock.app.api.ConnectionStore.state.value.config!!
        val cache = SyncOverviewCache(disk)
        try {
            cache.save(7, SyncOverview(stats = StatsResponse(completedCount = 4)))
            com.planlock.app.api.TokenStore.changeConnection(disk, original.copy(apiBaseUrl = "https://other.invalid/api/v1/"))
            assertNull(cache.read(7).stats("week"))
            cache.save(7, SyncOverview(stats = StatsResponse(completedCount = 9)))
            com.planlock.app.api.TokenStore.changeConnection(disk, original)
            assertEquals(4, cache.read(7).stats("week")?.completedCount)
        } finally {
            com.planlock.app.api.TokenStore.restore(MemoryStore())
        }
    }
    @Test fun retainedAccountOverviewStaysReachableWithoutUsingItsOldCredentials() {
        val account = com.planlock.app.api.AuthSession("token", 99, 8, signedIn = true)
        val signedOut = com.planlock.app.api.AuthState(signedOut = true, dataOwner = 42)
        assertEquals(42L, localOverviewOwner(null, signedOut))
        assertEquals(42L, localOverviewOwner(99, signedOut.copy(session = account, signedOut = false)))
        assertEquals(99L, localOverviewOwner(99, com.planlock.app.api.AuthState(session = account, dataOwner = 99)))
        assertNull(localOverviewOwner(null, com.planlock.app.api.AuthState()))
    }
    private class MemoryStore : DataStore<Preferences> {
        val values = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }
    @Test fun cacheSurvivesReaderRecreationAndNeverLeaksToAnotherUser() = runBlocking {
        val disk = MemoryStore()
        SyncOverviewCache(disk).save(7, SyncOverview(statsByPeriod = mapOf(
            "day" to StatsResponse(period = "day", completedCount = 1),
            "week" to StatsResponse(period = "week", completedCount = 4),
            "month" to StatsResponse(period = "month", completedCount = 9),
        )))
        val recreated = SyncOverviewCache(disk).read(7)
        assertEquals(1, recreated.stats("day")?.completedCount)
        assertEquals(4, recreated.stats("week")?.completedCount)
        assertEquals(9, recreated.stats("month")?.completedCount)
        assertNull(SyncOverviewCache(disk).read(8).stats("week"))
        assertNull(SyncOverviewCache(disk).read(null).stats("week"))
    }
    @Test fun legacySingleWeekSnapshotRemainsReadable() {
        val legacy = SyncOverview(stats = StatsResponse(completedCount = 6))
        assertEquals(6, legacy.stats("week")?.completedCount)
        assertNull(legacy.stats("day"))
    }
    @Test fun missingSnapshotIsNotAReadFailure() = runBlocking {
        assertEquals(SyncOverview(), SyncOverviewCache(MemoryStore()).read(7))
    }
    @Test fun diskFailureIsNotMisreportedAsAnEmptySnapshot() = runBlocking {
        val disk = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
        }
        try { SyncOverviewCache(disk).read(7); fail("Expected read failure") }
        catch (_: IOException) { }
    }
}
