package com.planlock.app.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InsightTest {
    @Test fun conversationFieldsAndAllReviewStatesRoundTrip() {
        InsightStatus.entries.forEach { status ->
            val insight = Insight(
                id = "insight-1", dataScope = "https://instance.invalid/api/v1/\n42",
                text = "你喜欢在上午安排阅读", status = status,
                stage = InsightStage.FIRST_IMPRESSION,
                evidence = "依据：你在对话中提到上午阅读更专注",
                sourceRefs = listOf(InsightReference("conversation-1", "message-2")),
                source = InsightSource.CONVERSATION, generatorId = "conversation-agent-v1",
                createdAt = 1_792_000_000_000L, updatedAt = 1_792_000_001_000L,
            )
            val encoded = Json.encodeToString(insight)
            assertTrue(encoded.contains("\"source\":\"conversation\""))
            assertEquals(insight, Json.decodeFromString<Insight>(encoded))
        }
    }
}
