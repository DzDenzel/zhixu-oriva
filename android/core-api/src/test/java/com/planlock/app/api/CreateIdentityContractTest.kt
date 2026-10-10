package com.planlock.app.api
import com.planlock.app.api.dto.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.Assert.*
class CreateIdentityContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun scheduleRetryRetainsPersistedIdentity() {
        val request = json.decodeFromString<ScheduleUpsertRequest>("""{"title":"offline","startDate":"2026-10-09","endDate":"2026-10-09","clientId":"fixed-create"}""")
        assertTrue(json.encodeToString(request).contains("fixed-create"))
    }
    @Test fun planSyncAcknowledgementRetainsMappingIdentity() {
        val result = json.decodeFromString<SyncItemResult>("""{"key":"plan:0","ok":true,"id":42,"version":1,"clientId":"fixed-create"}""")
        val encoded = json.encodeToString(result)
        assertTrue(encoded.contains("fixed-create"))
        assertTrue(encoded.contains("42"))
    }
}
