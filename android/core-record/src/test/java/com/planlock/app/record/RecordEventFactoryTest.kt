package com.planlock.app.record

import com.planlock.app.record.entity.PendingRecordEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import java.util.GregorianCalendar

class RecordEventFactoryTest {
    private val factory = RecordEventFactory(
        timeZone = TimeZone.getTimeZone("UTC"),
        randomClientId = { "random-id" }
    )
    private val plan = PlanSnapshotEntity(7, 1, "学习", "学习", "09:00:00", "10:00:00", null, null, null, 15, null, 1, 0)

    @Test
    fun `execution edge requests use deterministic ids and expected status`() {
        val start = factory.executionStarted(plan, 1_788_861_600_000)
        val end = factory.executionEnded(plan, 1_788_865_200_000, completed = true)

        assertEquals(0, start.status)
        assertTrue(start.clientId.endsWith("-start"))
        assertEquals(start.clientId, factory.executionStarted(plan, 1_788_861_600_000).clientId)
        assertEquals(1, end.status)
        assertTrue(end.clientId.endsWith("-end"))
    }

    @Test
    fun `pending rows map to all three exact endpoint payload types`() {
        val execution = PendingRecordEntity(type = "execution", planId = 1, planDate = "2026-09-20", status = 1, clientId = "e")
        val violation = PendingRecordEntity(type = "violation", planId = 2, recordAt = "2026-09-20T12:00:00", packageName = "pkg", clientId = "v")
        val unlock = PendingRecordEntity(type = "unlock", planId = 3, unlockAt = "2026-09-20T12:01:00", method = "backup_password", clientId = "u")

        assertEquals("e", (execution.toPendingUpload() as PendingUpload.Execution).request.clientId)
        assertEquals("pkg", (violation.toPendingUpload() as PendingUpload.Violation).request.packageName)
        assertEquals("backup_password", (unlock.toPendingUpload() as PendingUpload.Unlock).request.method)
    }

    @Test
    fun `unchanged execution has no edge and handoff emits one end and one start`() {
        assertEquals(ExecutionEdges(), ExecutionEdgeDetector.detect(7, 7))
        assertEquals(ExecutionEdges(endedPlanId = 7, startedPlanId = 8), ExecutionEdgeDetector.detect(7, 8))
    }

    @Test
    fun `cross day execution keeps the schedule start date`() {
        val crossDay = plan.copy(startTime = "23:00:00", endTime = "01:00:00")
        val afterMidnightMillis = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            set(2026, 8, 21, 0, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val afterMidnight = factory.executionEnded(crossDay, afterMidnightMillis, completed = true)

        assertEquals("2026-09-20", afterMidnight.planDate)
    }
}
