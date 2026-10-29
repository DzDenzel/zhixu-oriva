package com.planlock.app

import org.junit.Assert.*
import org.junit.Test

class PlanLiveIdentityTest {
    @Test fun dismissedWaitingIsNotRepostedWhenTheSameOccurrenceStarts() {
        val waiting = PlanLiveIdentity.occurrence("local", 7, 12000)
        val running = PlanLiveIdentity.occurrence("local", 7, 12000)
        assertFalse(PlanLiveIdentity.mayPublish(waiting, running))
    }
    @Test fun theNextOccurrenceMayPublishAgain() {
        val old = PlanLiveIdentity.occurrence("local", 7, 12000)
        assertTrue(PlanLiveIdentity.mayPublish(old, PlanLiveIdentity.occurrence("local", 7, 24000)))
    }
    @Test fun dismissalCannotAffectAnotherOwnerOrPlan() {
        val old = PlanLiveIdentity.occurrence("ownerA", 7, 12000)
        assertTrue(PlanLiveIdentity.mayPublish(old, PlanLiveIdentity.occurrence("ownerB", 7, 12000)))
        assertTrue(PlanLiveIdentity.mayPublish(old, PlanLiveIdentity.occurrence("ownerA", 8, 12000)))
    }
}
