package com.planlock.app.record

import com.planlock.app.api.Insight
import com.planlock.app.api.InsightFeedback
import com.planlock.app.api.InsightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** REQ-042 / CHANGE-061: no generation, storage or sync until conversation integration. */
class LocalInsightRepository(override val dataScope: String) : InsightRepository {
    override fun observe(): Flow<List<Insight>> = flowOf(emptyList())

    override suspend fun setFeedback(insightId: String, feedback: InsightFeedback) = Unit
}
