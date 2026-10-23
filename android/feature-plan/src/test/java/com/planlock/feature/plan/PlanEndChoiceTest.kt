package com.planlock.feature.plan

import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PlanEndChoiceTest {
    private val plan = PlanSnapshotEntity(1, 1, "专注", "学习", "09:00:00", "11:00:00", null, null, null, 0, null, 1, 0)
    private fun clock(day: Int = 16, hour: Int = 10) = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear(); set(2026, Calendar.OCTOBER, day, hour, 0)
    }

    @Test fun `only running end actions request a choice`() {
        val date = OccurrenceDate.from(clock())
        assertTrue(needsEndChoice(plan, ProtectedPlanAction.PAUSE, 600, date))
        assertFalse(needsEndChoice(plan.copy(status = 0), ProtectedPlanAction.PAUSE, 600, date))
        assertFalse(needsEndChoice(plan, ProtectedPlanAction.PAUSE, 720, date))
        assertFalse(needsEndChoice(plan, ProtectedPlanAction.EDIT, 600, date))
        assertFalse(needsEndChoice(plan.copy(endedOccurrenceDate = "2026-10-16"), ProtectedPlanAction.PAUSE, 600, date))
    }

    @Test fun `daily weekly monthly and unknown next repeats use actual next start`() {
        assertEquals("明天 09:00", nextRepeatDescription(plan, clock()))
        assertEquals("周一 09:00", nextRepeatDescription(plan.copy(repeatRule = "{\"type\":\"weekly\",\"weekdays\":[1]}"), clock()))
        assertEquals("10月20日 09:00", nextRepeatDescription(plan.copy(repeatRule = "{\"type\":\"monthly\",\"day\":20}"), clock()))
        assertEquals("下一次", nextRepeatDescription(plan.copy(repeatRule = "invalid"), clock()))
        assertEquals("下一次", nextRepeatDescription(plan.copy(startTime = "bad"), clock()))
    }

    @Test fun `cross midnight early end describes the next occurrence starting later today`() {
        val overnight = plan.copy(startTime = "23:00", endTime = "02:00")
        assertEquals("今天 23:00", nextRepeatDescription(overnight, clock(17, 1)))
        assertTrue(needsEndChoice(overnight, ProtectedPlanAction.PAUSE, 60, OccurrenceDate.from(clock(17, 1))))
    }

    @Test fun `choice stage performs neither password lookup nor writes and cancellation stays empty`() = runBlocking {
        var prompts = 0
        var checks = 0
        var writes = 0
        dispatchPlanAction(
            PendingPlanAction(plan, ProtectedPlanAction.PAUSE, false, onCompleted = { fail("Not completed") }),
            choosingEnd = true,
            requirement = { checks++; PlanActionRequirement.PASSWORD },
            perform = { writes++; PlanActionResult.Completed() },
            onPrompt = { prompts++; assertTrue(it.choosingEnd) },
        )
        // Dismissing the returned prompt has no continuation to execute.
        assertEquals(1, prompts)
        assertEquals(0, checks)
        assertEquals(0, writes)
    }

    @Test fun `selected choice prompts for password before writing and survives prompt`() = runBlocking {
        var writes = 0
        dispatchPlanAction(
            PendingPlanAction(plan, ProtectedPlanAction.PAUSE, false, onCompleted = {}, endChoice = EndPlanChoice.TODAY),
            choosingEnd = false,
            requirement = { PlanActionRequirement.PASSWORD },
            perform = { writes++; PlanActionResult.Completed() },
            onPrompt = { assertFalse(it.choosingEnd); assertEquals(EndPlanChoice.TODAY, it.endChoice) },
        )
        assertEquals(0, writes)
    }

    @Test fun `selected choice with default free gate runs once without password prompt`() = runBlocking {
        var writes = 0
        var completions = 0
        dispatchPlanAction(
            PendingPlanAction(plan, ProtectedPlanAction.PAUSE, false, onCompleted = { completions++ }, endChoice = EndPlanChoice.ENTIRE_PLAN),
            choosingEnd = false,
            requirement = { PlanActionRequirement.FREE },
            perform = { writes++; PlanActionResult.Completed() },
            onPrompt = { fail("No password prompt") },
        )
        assertEquals(1, writes)
        assertEquals(1, completions)
    }
}
