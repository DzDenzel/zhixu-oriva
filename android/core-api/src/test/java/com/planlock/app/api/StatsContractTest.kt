package com.planlock.app.api

import com.planlock.app.api.dto.StatsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsContractTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun oldBackendPayloadUsesBackwardCompatibleReportDefaults() {
        val value = json.decodeFromString<StatsResponse>(
            """{"period":"week","completedCount":2,"futureField":true}""",
        )

        assertEquals(2, value.completedCount)
        assertEquals(0L, value.violationDurationMs)
        assertEquals(emptyList<Any>(), value.violationByApp)
        assertEquals(emptyList<Any>(), value.planExecution)
        assertEquals(emptyList<Any>(), value.earlyEndings)
        assertEquals(0, value.legacyViolationCount)
    }

    @Test
    fun newBackendPayloadKeepsLongDurationsAndNullableApplicationName() {
        val value = json.decodeFromString<StatsResponse>(
            """{"period":"day","violationDurationMs":3600000,"violationByApp":[{"packageName":"app.chat","appName":null,"count":2,"totalDurationMs":3600000,"approxDurationMs":1200000}]}""",
        )

        assertEquals(3_600_000L, value.violationDurationMs)
        assertNull(value.violationByApp.single().appName)
        assertEquals(1_200_000L, value.violationByApp.single().approxDurationMs)
    }
}
