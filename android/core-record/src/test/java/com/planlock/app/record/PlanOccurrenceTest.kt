package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanOccurrenceTest {
    private val base = PlanSnapshotEntity(7, 1, "学习", "学习", "09:00:00", "10:00:00", null, null, null, 15, null, 1, 0)
    private fun date(isoDay: Int) = OccurrenceDate(2026, 10, 4 + isoDay, isoDay)

    @Test fun dailyRuleRunsOnAllSevenDays() {
        val plan = base.copy(repeatRule = "{\"type\":\"daily\"}")
        (1..7).forEach { assertTrue("day $it", PlanOccurrence.isActive(plan, 570, date(it))) }
    }

    @Test fun weekdayRuleStopsOnWeekend() {
        val plan = base.copy(repeatRule = "{\"type\":\"weekly\",\"weekdays\":[1,2,3,4,5]}")
        (1..7).forEach { assertMatchesDay(plan, it, it <= 5) }
    }

    @Test fun weekendRuleStopsOnWeekdays() {
        val plan = base.copy(repeatRule = "{\"type\":\"weekly\",\"weekdays\":[6,7]}")
        (1..7).forEach { assertMatchesDay(plan, it, it >= 6) }
    }

    @Test fun customRuleOnlyRunsOnSelectedDays() {
        val plan = base.copy(repeatRule = "{\"type\":\"weekly\",\"weekdays\":[2,4]}")
        (1..7).forEach { assertMatchesDay(plan, it, it == 2 || it == 4) }
    }

    @Test fun malformedAndUnknownRulesAreRejectedInsteadOfRunningDaily() {
        val values = listOf(null, "", "{}", "not-json", "{\"type\":\"other\"}", "{\"type\":\"weekly\",\"weekdays\":[]}", "{\"type\":\"weekly\",\"weekdays\":[8]}")
        values.forEach { raw ->
            val plan = base.copy(repeatRule = raw)
            (1..7).forEach { day ->
                val expected = raw.isNullOrBlank()
                if (expected) assertTrue("rule=$raw day=$day", PlanOccurrence.isActive(plan, 570, date(day)))
                else assertFalse("rule=$raw day=$day", PlanOccurrence.isActive(plan, 570, date(day)))
            }
        }
    }

    @Test fun fiveRuleKindsMatchExactCalendarDates() {
        val march5Monday = OccurrenceDate(2029, 3, 5, 1)
        val march6Tuesday = OccurrenceDate(2029, 3, 6, 2)
        val april5Thursday = OccurrenceDate(2029, 4, 5, 4)

        assertTrue(PlanOccurrence.occursOn(null, march5Monday))
        assertTrue(PlanOccurrence.occursOn("{\"type\":\"daily\"}", march6Tuesday))
        assertTrue(PlanOccurrence.occursOn("{\"type\":\"weekly\",\"weekdays\":[1]}", march5Monday))
        assertFalse(PlanOccurrence.occursOn("{\"type\":\"weekly\",\"weekdays\":[1]}", march6Tuesday))
        assertTrue(PlanOccurrence.occursOn("{\"type\":\"monthly\",\"day\":5}", april5Thursday))
        assertFalse(PlanOccurrence.occursOn("{\"type\":\"monthly\",\"day\":5}", march6Tuesday))
        assertTrue(PlanOccurrence.occursOn("{\"type\":\"yearly\",\"month\":3,\"day\":5}", march5Monday))
        assertFalse(PlanOccurrence.occursOn("{\"type\":\"yearly\",\"month\":3,\"day\":5}", april5Thursday))
    }

    @Test fun supportVocabularyRejectsInvalidMonthlyAndYearlyRules() {
        listOf(null, "", "{\"type\":\"daily\"}", "{\"type\":\"weekly\",\"weekdays\":[1]}", "{\"type\":\"monthly\",\"day\":31}", "{\"type\":\"yearly\",\"month\":12,\"day\":31}")
            .forEach { assertTrue("supported $it", PlanOccurrence.isSupportedRule(it)) }
        listOf("{}", "{\"type\":\"monthly\",\"day\":0}", "{\"type\":\"yearly\",\"month\":13,\"day\":1}", "{\"type\":\"unknown\"}")
            .forEach { assertFalse("unsupported $it", PlanOccurrence.isSupportedRule(it)) }
    }

    @Test fun overnightTailUsesFridayAsStartDayAndEndsAtSix() {
        val plan = base.copy(startTime = "21:00:00", endTime = "06:00:00", repeatRule = "{\"type\":\"weekly\",\"weekdays\":[1,2,3,4,5]}")
        assertTrue(PlanOccurrence.isActive(plan, 21 * 60, date(5)))
        assertTrue(PlanOccurrence.isActive(plan, 30, date(6)))
        assertTrue(PlanOccurrence.isActive(plan, 5 * 60 + 59, date(6)))
        assertFalse(PlanOccurrence.isActive(plan, 6 * 60, date(6)))
        assertFalse(PlanOccurrence.isActive(plan, 21 * 60, date(7)))
    }

    @Test fun overnightSundayTailDoesNotUseMondayRule() {
        val plan = base.copy(startTime = "21:00", endTime = "06:00", repeatRule = "{\"type\":\"weekly\",\"weekdays\":[1]}")
        assertFalse(PlanOccurrence.isActive(plan, 30, date(1)))
        assertTrue(PlanOccurrence.isActive(plan, 21 * 60, date(1)))
    }

    @Test fun pausedOvernightOccurrenceUsesPreviousStartDay() {
        val fridayOnly = base.copy(
            status = 0,
            startTime = "23:00",
            endTime = "20:00",
            repeatRule = "{\"type\":\"weekly\",\"weekdays\":[5]}",
        )
        val saturdayOnly = fridayOnly.copy(repeatRule = "{\"type\":\"weekly\",\"weekdays\":[6]}")

        assertTrue(PlanOccurrence.isWithinOccurrence(fridayOnly, 30, date(6)))
        assertFalse(PlanOccurrence.isWithinOccurrence(saturdayOnly, 30, date(6)))
    }

    @Test fun pausedPlanNeverRunsEvenOnSelectedDay() {
        val plan = base.copy(status = 0, repeatRule = "{\"type\":\"daily\"}")
        (1..7).forEach { assertFalse(PlanOccurrence.isActive(plan, 570, date(it))) }
    }

    @Test fun invalidOrEqualTimeNeverRuns() {
        assertFalse(PlanOccurrence.isActive(base.copy(startTime = "bad"), 570, date(1)))
        assertFalse(PlanOccurrence.isActive(base.copy(startTime = "09:00", endTime = "09:00"), 570, date(1)))
        assertFalse(PlanOccurrence.isActive(base, 600, date(1)))
    }

    @Test fun occurrenceDayCanDriveAgendaIndependentOfClockAndStatus() {
        val plan = base.copy(status = 0, repeatRule = "{\"type\":\"weekly\",\"weekdays\":[2,4]}")
        assertFalse(PlanOccurrence.occursOn(plan, date(1)))
        assertTrue(PlanOccurrence.occursOn(plan, date(2)))
        assertTrue(PlanOccurrence.occursOn(plan, date(4)))
    }

    private fun assertMatchesDay(plan: PlanSnapshotEntity, day: Int, expected: Boolean) {
        if (expected) assertTrue("day $day", PlanOccurrence.isActive(plan, 570, date(day)))
        else assertFalse("day $day", PlanOccurrence.isActive(plan, 570, date(day)))
    }
}
