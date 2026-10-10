package com.planlock.app.api

import com.planlock.app.api.dto.PlanUpsertRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PlanReminderContractTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val fields = """"name":"只改标题","type":"学习","startTime":"09:00:00","endTime":"10:00:00""""
    @Test fun editedExplicitNullIsNotCoercedIntoTheCreateDefault() {
        val request = json.decodeFromString<PlanUpsertRequest>("{$fields,\"remindAdvanceMin\":null}")
        assertNull(request.remindAdvanceMin)
        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        assertTrue(encoded.containsKey("remindAdvanceMin")); assertEquals(JsonNull, encoded["remindAdvanceMin"])
    }
    @Test fun newRequestWithOmittedReminderStillDefaultsToFifteen() {
        val request = json.decodeFromString<PlanUpsertRequest>("{$fields}")
        assertEquals(15, request.remindAdvanceMin)
    }
}
