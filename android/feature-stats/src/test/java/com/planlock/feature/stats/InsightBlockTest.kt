package com.planlock.feature.stats

import com.planlock.app.api.InsightFeedback
import com.planlock.app.api.InsightStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 知行任务包 K2 的 L1 部分：夹具 2 条时显示 2 条，待确认条目带「不像 / 像我」；空列表不显示任何条目。 */
class InsightBlockTest {
    @Test fun fixtureShowsTwoAndOnlyPendingHasBothActions() {
        val shown = visibleInsights(insightFixture())
        assertEquals(2, shown.size)
        val pending = shown.single { it.status == InsightStatus.PENDING }
        assertEquals(listOf("不像", "像我"), insightActions(pending).map(::insightActionLabel))
        assertTrue(insightActions(shown.single { it.status == InsightStatus.CONFIRMED }).isEmpty())
    }

    @Test fun emptyAndRejectedOnlyRenderNothing() {
        assertTrue(visibleInsights(emptyList()).isEmpty())
        assertTrue(visibleInsights(insightFixture().map { it.copy(status = InsightStatus.REJECTED) }).isEmpty())
    }

    @Test fun actionOrderMatchesFeedbackMeaning() {
        assertEquals(listOf(InsightFeedback.UNLIKE_ME, InsightFeedback.LIKE_ME), insightActions(insightFixture().first()))
    }
}
