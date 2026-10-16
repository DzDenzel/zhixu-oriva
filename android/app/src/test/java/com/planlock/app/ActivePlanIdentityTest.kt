package com.planlock.app

import com.planlock.app.record.ExecutionEdgeDetector
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActivePlanIdentityTest {
    private val cached = PlanSnapshotEntity(-101,1,"cached","study","10:00:00","11:00:00",null,"blue","book",15,null,1,0)
    private suspend fun resolve(id: Long) = if(id == -101L) 41L else id

    @Test fun mappingAloneDoesNotProduceExecutionStartOrEnd() = runBlocking {
        val identity=activePlanIdentity(-101,cached,::resolve)
        val edges=ExecutionEdgeDetector.detect(identity.previousId,41)
        assertNull(edges.startedPlanId); assertNull(edges.endedPlanId)
    }
    @Test fun actualPlanHandoffKeepsBothExistingEdges() = runBlocking {
        val identity=activePlanIdentity(-101,cached,::resolve)
        val edges=ExecutionEdgeDetector.detect(identity.previousId,42)
        assertEquals(41L,edges.endedPlanId); assertEquals(42L,edges.startedPlanId)
    }
    @Test fun endedMappedPlanKeepsCachedBusinessFieldsUnderCanonicalIdentity() = runBlocking {
        val identity=activePlanIdentity(-101,cached,::resolve)
        val edges=ExecutionEdgeDetector.detect(identity.previousId,null)
        assertEquals(41L,edges.endedPlanId)
        assertEquals(cached.copy(id=41),identity.cachedPlan)
    }
    @Test fun mappingAfterPlanSnapshotUsesOneCanonicalIdentityForBothSides() = runBlocking {
        val identity=activePlanIdentity(-101,cached,::resolve,activeSnapshotId=-101)
        val edges=ExecutionEdgeDetector.detect(identity.previousId,identity.activeId)
        assertEquals(41L,identity.activeId); assertNull(edges.startedPlanId); assertNull(edges.endedPlanId)
    }
}
