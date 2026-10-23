package com.planlock.feature.plan

import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.design.FormIssue
import com.planlock.design.RepeatPreset
import com.planlock.design.saveBarHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorValidationTest {
    private val newDraft = ScheduleEditDraft.fromSnapshot(null)

    @Test fun planNameAndEmptyCustomRepeatInPageOrder() {
        assertEquals(listOf(PlanField.NAME, PlanField.REPEAT),
            planFormIssues(" ", RepeatPreset.CUSTOM, emptySet()).map { it.field })
        assertTrue(planFormIssues("背单词", RepeatPreset.WEEKDAY, setOf(1, 2, 3, 4, 5)).isEmpty())
        assertEquals("请填写计划名称", planFormIssues("", RepeatPreset.DAILY, (1..7).toSet()).single().message)
    }

    @Test fun validScheduleHasNoIssues() {
        assertTrue(scheduleFormIssues("体检", "2026-10-20", "2026-10-20", false, newDraft).isEmpty())
    }

    @Test fun scheduleIssuesFollowPageOrder() {
        val issues = scheduleFormIssues("", "2026-10-21", "2026-10-20", false, newDraft.withStartTime("").withEndTime("10:00"))
        assertEquals(listOf(ScheduleField.TITLE, ScheduleField.DATE, ScheduleField.TIME), issues.map { it.field })
        assertEquals("结束日期不能早于开始日期", issues[1].message)
    }

    @Test fun sameDayEndBeforeStartIsAnIssueButAllDayIsNot() {
        val draft = newDraft.withStartTime("15:00").withEndTime("09:00")
        assertEquals("结束时间要晚于开始时间", scheduleFormIssues("会", "2026-10-20", "2026-10-20", false, draft).single().message)
        assertTrue(scheduleFormIssues("会", "2026-10-20", "2026-10-20", true, draft).isEmpty())
        assertTrue(scheduleFormIssues("会", "2026-10-20", "2026-10-21", false, draft).isEmpty())
    }

    @Test fun unsupportedLegacyRuleAsksToChooseAgain() {
        val legacy = ScheduleSnapshotEntity(1, 1, "旧", "2026-10-20", "09:00", "2026-10-20", "10:00", false, null, null, null,
            """{"type":"weird"}""", null, tzId = null, status = 1, version = 1)
        val issues = scheduleFormIssues("旧", "2026-10-20", "2026-10-20", false, ScheduleEditDraft.fromSnapshot(legacy))
        assertEquals(listOf(ScheduleField.REPEAT), issues.map { it.field })
    }

    @Test fun saveBarHintShowsFirstIssuePlusRemainder() {
        val issues = (1..5).map { FormIssue("f$it", "第 $it 项") }
        assertEquals("第 1 项，还有 4 项", saveBarHint(issues))
        assertEquals("第 1 项", saveBarHint(issues.take(1)))
        assertEquals(null, saveBarHint(emptyList()))
    }
}
