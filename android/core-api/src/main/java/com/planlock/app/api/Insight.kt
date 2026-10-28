package com.planlock.app.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An understanding derived from a conversation, never from execution statistics. */
@Serializable
data class Insight(
    val id: String,
    val dataScope: String,
    val text: String,
    val status: InsightStatus,
    val stage: InsightStage,
    val evidence: String,
    val sourceRefs: List<InsightReference>,
    val source: InsightSource,
    val generatorId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
enum class InsightStatus { PENDING, CONFIRMED, REJECTED }

@Serializable
enum class InsightStage { FIRST_IMPRESSION }

@Serializable
enum class InsightSource {
    @SerialName("conversation") CONVERSATION,
}

/** Stable conversation/message IDs; the readable basis is kept in Insight.evidence. */
@Serializable
data class InsightReference(val conversationId: String, val messageId: String)

enum class InsightFeedback { LIKE_ME, UNLIKE_ME }

/** Bind an instance to AuthState.dataScope; recreate it when the visible identity changes. */
interface InsightRepository {
    val dataScope: String
    fun observe(): Flow<List<Insight>>

    /** v2.1.2 reserves this signature; the local implementation does not persist feedback. */
    suspend fun setFeedback(insightId: String, feedback: InsightFeedback)
}
