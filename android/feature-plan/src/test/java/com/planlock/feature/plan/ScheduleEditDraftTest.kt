package com.planlock.feature.plan

import com.planlock.app.api.AssistantScheduleDraft
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import org.junit.Assert.*
import org.junit.Test

class ScheduleEditDraftTest {
    @Test fun unsupportedRuleCannotReachTheExistingSaveEntryUntilExplicitlyReplaced() {
        for (rule in listOf("""{"type":"custom"}""", "legacy raw rule", """{"type":"weekly"}""", """{"type":"monthly","day":40}""")) {
            val draft = ScheduleEditDraft.fromSnapshot(saved(rule = rule))
            assertFalse("Rule rejected by the real queue must disable save: $rule", draft.ruleCanBeSaved)
            assertTrue(draft.showsPreservedRule)
            assertEquals(rule, draft.rename().repeatRule)
            assertTrue(draft.withRepeatType(null).ruleCanBeSaved)
            assertTrue(draft.withRepeatType("daily").ruleCanBeSaved)
        }
    }

    @Test fun supportedAndNullRulesRemainSavableWithoutRewritingThem() {
        for (rule in listOf(null, """{"type":"weekly","weekdays":[2,4],"count":7}""")) {
            val draft = ScheduleEditDraft.fromSnapshot(saved(rule = rule))
            assertTrue(draft.ruleCanBeSaved)
            assertFalse(draft.showsPreservedRule)
            assertEquals(rule, draft.rename().repeatRule)
        }
    }

    private fun saved(start: String? = null, end: String? = null, reminder: Int? = null, rule: String? = null) =
        ScheduleSnapshotEntity(80, 7, "原日程", "2026-10-09", start, "2026-10-09", end, false,
            "  ", "保留备注", reminder, rule, null, tzId = "Europe/Paris", status = 1, version = 3)
    private fun ScheduleEditDraft.rename(allDay: Boolean = false) = request(
        "只改标题", "2026-10-09", "2026-10-09", allDay, "  ", "保留备注", null, "Asia/Shanghai")

    @Test fun renameKeepsUnsetReminderAndTimesUnsetAndShowsEmptyFields() {
        val draft = ScheduleEditDraft.fromSnapshot(saved())
        assertEquals("", draft.reminder)
        assertEquals("", draft.startTime)
        assertEquals("", draft.endTime)
        val request = draft.rename()
        assertNull(request.remindAdvanceMin); assertNull(request.startTime); assertNull(request.endTime)
        assertTrue(draft.timesValid(false))
    }
    @Test fun oneUnsetTimeDoesNotChangeTheOtherStoredSeconds() {
        val request = ScheduleEditDraft.fromSnapshot(saved(end = "10:12:37")).rename()
        assertNull(request.startTime); assertEquals("10:12:37", request.endTime)
    }
    @Test fun onlyConfirmedTimeReplacesTheOriginalSeconds() {
        val draft = ScheduleEditDraft.fromSnapshot(saved("09:00:37", "10:12:44"))
        assertEquals("09:00:37", draft.rename().startTime)
        val changed = draft.withStartTime("11:23").rename()
        assertEquals("11:23", changed.startTime); assertEquals("10:12:44", changed.endTime)
    }
    @Test fun allDayStillClearsBothTimes() {
        val request = ScheduleEditDraft.fromSnapshot(saved("09:00:37", "10:12:44")).rename(allDay = true)
        assertNull(request.startTime); assertNull(request.endTime)
    }
    @Test fun explicitZeroReminderDiffersFromUnsetAndBlankClearsIt() {
        val draft = ScheduleEditDraft.fromSnapshot(saved(reminder = null))
        assertNull(draft.rename().remindAdvanceMin)
        assertEquals(0, draft.withReminder("0").rename().remindAdvanceMin)
        assertNull(draft.withReminder("").rename().remindAdvanceMin)
    }
    @Test fun overflowIsInvalidInsteadOfSilentlyClearingTheReminder() {
        assertFalse(ScheduleEditDraft.fromSnapshot(saved(reminder = 30)).withReminder("999999999999").reminderValid)
    }
    @Test fun newScheduleKeepsTheExistingProductDefaults() {
        val draft = ScheduleEditDraft.fromSnapshot(null)
        val request = draft.rename()
        assertEquals("09:00", request.startTime); assertEquals("10:00", request.endTime)
        assertEquals(15, request.remindAdvanceMin); assertTrue(draft.timesValid(false));assertTrue(draft.reminderValid)
    }
    @Test fun assistantUnconfirmedTimesRemainEmptyAndCannotBeSaved() {
        val assistant = AssistantScheduleDraft("待确认", "2026-10-09", allDay = false, needsTimeConfirmation = true)
        val draft = ScheduleEditDraft.fromSnapshot(null, assistant)
        assertEquals("", draft.startTime); assertEquals("", draft.endTime)
        assertFalse(draft.timesValid(false))
    }
    @Test fun unknownRuleIsPreservedUntilAnExplicitRepeatSelection() {
        val raw = """{"type":"custom","interval":3,"extension":{"keep":true}}"""
        val draft = ScheduleEditDraft.fromSnapshot(saved(rule = raw))
        assertTrue(draft.showsPreservedRule)
        assertEquals(raw, draft.rename().repeatRule)
        assertNull(draft.withRepeatType(null).rename().repeatRule)
    }
    @Test fun knownRuleDoesNotLoseItsDaysOrExtensionWhenRenamed() {
        val raw = """{"type":"weekly","weekdays":[2,4],"count":7}"""
        val draft = ScheduleEditDraft.fromSnapshot(saved(rule = raw))
        assertEquals(raw, draft.rename().repeatRule)
        assertEquals("""{"type":"weekly","weekdays":[5]}""", draft.withRepeatType("weekly").rename().repeatRule)
    }
    @Test fun malformedLegacyRuleIsNotDisplayedOrSavedAsOneOff() {
        val raw = "legacy-rule: untouched"
        val draft = ScheduleEditDraft.fromSnapshot(saved(rule = raw))
        assertTrue(draft.showsPreservedRule); assertEquals(raw, draft.rename().repeatRule)
    }
    @Test fun nullRuleRemainsOneOff() {
        val draft = ScheduleEditDraft.fromSnapshot(saved(rule = null))
        assertFalse(draft.showsPreservedRule); assertNull(draft.rename().repeatRule)
    }
    @Test fun renameDoesNotRewriteTimezoneOrNormalizeUntouchedOptionalText() {
        val request = ScheduleEditDraft.fromSnapshot(saved()).rename()
        assertEquals("Europe/Paris", request.tzId); assertEquals("  ", request.location)
    }
}
