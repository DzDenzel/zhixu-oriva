package com.planlock.app.api

import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.ViolationAppStat
import org.junit.Assert.*
import org.junit.Test

class AssistantCapabilitiesTest {
    private val snapshot = AssistantSnapshot(today = "2026-10-05")
    private fun reply(text: String, value: AssistantSnapshot = snapshot) = AssistantCapabilityRegistry.respond(text, value)
    private fun draft(text: String) = (reply(text).actions.single() as AssistantAction.EditSchedule).draft

    @Test fun todayIncludesBothScheduleAndPlanData() {
        val result = reply("今天有什么安排", snapshot.copy(arrangements = listOf("全天 · 妈妈生日 · 日程", "08:00 → 09:00 · 阅读 · 计划")))
        assertTrue(result.text.contains("妈妈生日"))
        assertTrue(result.text.contains("阅读"))
        assertEquals(AssistantDestination.TODAY, (result.actions.single() as AssistantAction.OpenPage).destination)
    }

    @Test fun streakReadsTheSnapshotRatherThanInventingData() {
        assertTrue(reply("连续达成几天", snapshot.copy(continuousDays = 7)).text.contains("7 天"))
        assertTrue(reply("连续达成几天").text.contains("暂无报告数据"))
    }

    @Test fun reportUsesRequestedScopeWithDurationCountAndTopSources() {
        val report = StatsResponse(period = "week", violationCount = 3, violationDurationMs = 3600000,
            violationByApp = listOf(ViolationAppStat("bili", "哔哩哔哩", 1, 1800000), ViolationAppStat("wechat", "微信", 1, 1200000, 1200000)))
        val result = reply("这周分心多久", snapshot.copy(reports = mapOf("week" to report)))
        assertTrue(result.text.contains("1:00:00（时:分:秒）"))
        assertTrue(result.text.contains("3 次"))
        assertTrue(result.text.contains("哔哩哔哩 0:30:00"))
        assertTrue(result.text.contains("微信 0:20:00（含近似）"))
        assertTrue(reply("今天分心多久", snapshot.copy(reports = mapOf("week" to report))).text.contains("暂无报告数据"))
    }

    @Test fun missingReportOffersTheReportPage() {
        val result = reply("本月违规多久")
        assertTrue(result.text.contains("暂无报告数据"))
        assertEquals(AssistantDestination.STATS, (result.actions.single() as AssistantAction.OpenPage).destination)
    }

    @Test fun birthdayIsAnUncommittedAnnualAllDayDraftForNextOccurrence() {
        val result = draft("记一下 3 月 5 日是某人生日")
        assertEquals("某人生日", result.title)
        assertEquals("2027-03-05", result.startDate)
        assertTrue(result.allDay)
        assertEquals("yearly", result.repeatType)
    }

    @Test fun relativeDatesHandleWeekAndYearBoundaries() {
        assertEquals("2026-10-06", draft("记一下明天看展").startDate)
        assertEquals("2026-10-16", draft("记一下下周五开会").startDate)
        val result = AssistantCapabilityRegistry.respond("记一下明天看展", snapshot.copy(today = "2026-12-31"))
        assertEquals("2027-01-01", (result.actions.single() as AssistantAction.EditSchedule).draft.startDate)
    }

    @Test fun invalidAndMissingDatesStayEmpty() {
        assertNull(draft("记一下某人生日").startDate)
        assertNull(draft("记一下 2 月 30 日某人生日").startDate)
        assertNull(draft("记一下下个月开会").startDate)
    }

    @Test fun explicitYearAndTimeArePreserved() {
        val value = draft("记一下 2026 年 3 月 5 日下午3点开会")
        assertEquals("2026-03-05", value.startDate)
        assertEquals("15:00", value.startTime)
        assertEquals("16:00", value.endTime)
        assertFalse(value.allDay)
        assertEquals("开会", value.title)
    }

    @Test fun multiplePossibleDatesRequireExplicitSelection() {
        assertNull(draft("记一下明天或后天开会").startDate)
        assertNull(draft("记一下3月5日或3月6日开会").startDate)
    }

    @Test fun lateNightTimeCarriesTheEndIntoTheNextDate() {
        for (text in listOf("记一下明天23点开会", "记一下明天晚上11点开会")) {
            val value = draft(text)
            assertFalse(value.allDay)
            assertEquals("23:00", value.startTime)
            assertEquals("00:00", value.endTime)
            assertEquals("2026-10-07", value.endDate)
        }
        val value = draft("记一下明天23:30开会")
        assertEquals("23:30", value.startTime)
        assertEquals("00:30", value.endTime)
    }

    @Test fun invalidClockRequiresManualCompletionInsteadOfAllDay() {
        val value = draft("记一下明天25点开会")
        assertFalse(value.allDay)
        assertTrue(value.needsTimeConfirmation)
        assertNull(value.startTime)
        assertNull(value.endTime)
        assertTrue(value.title.contains("25点"))
    }

    @Test fun yearlessLeapDayFindsTheNextActualOccurrence() {
        assertEquals("2028-02-29", draft("记一下2月29日生日").startDate)
        assertEquals("2028-02-29", parseAssistantDraft("记一下2月29日生日", "2028-02-29").startDate)
        assertEquals("2032-02-29", parseAssistantDraft("记一下2月29日生日", "2028-03-01").startDate)
        assertNull(draft("记一下2026年2月29日生日").startDate)
    }

    @Test fun unknownInputHasClickableGuidanceAndExtractedDate() {
        val unknown = reply("帮我写一首诗")
        assertFalse(unknown.text.contains("尚未接入"))
        assertTrue(unknown.actions.any { it is AssistantAction.OpenPage })
        assertTrue(reply("3月5日某人生日").actions.any { it is AssistantAction.EditSchedule })
    }

    @Test fun endingAndStartingRequireAnExplicitDesiredState() {
        val paused = AssistantPlanState(1, "阅读", false, true, 30)
        val started = reply("开始阅读计划", snapshot.copy(plans = listOf(paused)))
        assertEquals(AssistantAction.ChangePlan("开始 阅读", 1, true), started.actions.single())
        val ended = reply("结束当前计划", snapshot.copy(plans = listOf(paused.copy(enabled = true))))
        assertEquals(false, (ended.actions.single() as AssistantAction.ChangePlan).enabled)
        assertTrue(reply("开始阅读计划", snapshot.copy(plans = listOf(paused.copy(enabled = true)))).actions.none { it is AssistantAction.ChangePlan })
    }

    @Test fun ambiguousAndOutOfWindowPlansAreNotChanged() {
        val plans = listOf(AssistantPlanState(1, "阅读", true, true, 30), AssistantPlanState(2, "运动", true, true, 40))
        assertTrue(reply("结束当前计划", snapshot.copy(plans = plans)).actions.none { it is AssistantAction.ChangePlan })
        assertTrue(reply("结束不存在计划", snapshot.copy(plans = plans)).actions.none { it is AssistantAction.ChangePlan })
        assertTrue(reply("开始阅读计划", snapshot.copy(plans = listOf(plans[0].copy(enabled = false, inWindow = false)))).actions.none { it is AssistantAction.ChangePlan })
    }

    @Test fun olderCurrentAndUnlockQueriesRemainUseful() {
        assertTrue(reply("当前计划还剩多久", snapshot.copy(plans = listOf(AssistantPlanState(1, "阅读", true, true, 30)))).text.contains("00:30"))
        assertTrue(reply("今天还剩几次解锁", snapshot.copy(todayUnlocks = 2)).text.contains("2 次"))
    }
}
