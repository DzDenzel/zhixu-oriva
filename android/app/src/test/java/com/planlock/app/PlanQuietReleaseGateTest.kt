package com.planlock.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlanQuietReleaseGateTest {
    @Test fun positiveSystemReadFollowsReconcileBeforeReturningReleased() = runBlocking {
        val events = mutableListOf<String>()
        var reads = 0
        val result = PlanQuietReleaseGate.await(
            reconcile = { events += "reconcile"; false },
            probe = { events += "probe"; ++reads >= 2 },
            elapsed = { reads.toLong() }, pause = { events += "wait" },
        )
        assertEquals(PlanQuietOutcome.RELEASED, result)
        assertEquals(listOf("reconcile", "probe", "wait", "probe"), events)
    }
    @Test fun missingListenerIsUnknownAndNeverSuccess() = runBlocking {
        val result = PlanQuietReleaseGate.await({ false }, { null }, { 0L }, { error("must not wait on disconnected listener") })
        assertEquals(PlanQuietOutcome.UNVERIFIED, result)
    }
    @Test fun anotherActivePlanKeepsQuietWithoutTryingToClearGlobalState() = runBlocking {
        val result = PlanQuietReleaseGate.await({ true }, { error("must not poll another plan away") }, { 0L }, { error("must not wait") })
        assertEquals(PlanQuietOutcome.OTHER_ACTIVE_PLAN, result)
    }
    @Test fun persistentSystemQuietHasBoundedWaitAndIsNotReleased() = runBlocking {
        var clock = 0L
        var probes = 0
        val result = PlanQuietReleaseGate.await({ false }, { probes++; false }, { clock }, { clock += 25 }, 100)
        assertEquals(PlanQuietOutcome.UNVERIFIED, result)
        assertEquals(5, probes)
    }
}
