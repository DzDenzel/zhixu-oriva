package com.planlock.feature.plan

import com.planlock.app.record.entity.StrategySnapshotEntity
import org.junit.Assert.*
import org.junit.Test

class PlanStrategyDraftTest {
    private fun row(autoDnd: Boolean?, notifySilence: Boolean?) = StrategySnapshotEntity(1, 2, null, null, autoDnd, notifySilence, 3)
    @Test fun renamingExistingPlanWithoutStrategyDoesNotCreateOne() {
        val draft = PlanStrategyDraft.fromSnapshot(null, isNewPlan = false)
        assertNull(draft.autoDnd)
        assertNull(draft.notifySilence)
        assertFalse(draft.shouldWriteStrategy)
    }
    @Test fun savedNullFieldsRemainUnconfiguredWhenOnlyPlanFieldsChange() {
        val draft = PlanStrategyDraft.fromSnapshot(row(null, null), isNewPlan = false)
        assertNull(draft.autoDnd)
        assertNull(draft.notifySilence)
        assertFalse(draft.shouldWriteStrategy)
    }
    @Test fun explicitSavedBooleansAreRetainedWithoutStrategyWrite() {
        for(auto in listOf(false,true)) for(notify in listOf(false,true)) {
            val draft=PlanStrategyDraft.fromSnapshot(row(auto,notify),false)
            assertEquals(auto,draft.autoDnd); assertEquals(notify,draft.notifySilence)
            assertFalse(draft.shouldWriteStrategy)
        }
    }
    @Test fun newPlanKeepsItsExistingEnabledDefaults() {
        val draft=PlanStrategyDraft.fromSnapshot(null,true)
        assertEquals(true,draft.autoDnd); assertEquals(true,draft.notifySilence)
        assertTrue(draft.shouldWriteStrategy)
    }
    @Test fun changingOneToggleDoesNotFillTheOtherUnconfiguredField() {
        val draft=PlanStrategyDraft.fromSnapshot(row(null,null),false).withAutoDnd(true)
        assertTrue(draft.shouldWriteStrategy); assertEquals(true,draft.autoDnd); assertNull(draft.notifySilence)
    }
    @Test fun togglingBackToSavedValueDoesNotForceRewrite() {
        val draft=PlanStrategyDraft.fromSnapshot(row(false,true),false).withNotifySilence(false).withNotifySilence(true)
        assertFalse(draft.shouldWriteStrategy)
    }
    @Test fun whitelistOnlyEditPreservesUnknownTogglesAndCanBeReverted() {
        val draft=PlanStrategyDraft.fromSnapshot(row(null,false),false).withWhitelistChanged(true)
        assertTrue(draft.shouldWriteStrategy); assertNull(draft.autoDnd); assertEquals(false,draft.notifySilence)
        assertFalse(draft.withWhitelistChanged(false).shouldWriteStrategy)
    }
    @Test fun bothPagesDistinguishUnknownDisabledAndEnabled() {
        assertEquals("未配置",strategyStateText(null))
        assertEquals("未开启",strategyStateText(false))
        assertEquals("已开启",strategyStateText(true))
    }
}
