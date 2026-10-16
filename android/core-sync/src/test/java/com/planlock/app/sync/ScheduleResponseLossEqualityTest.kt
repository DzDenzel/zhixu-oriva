package com.planlock.app.sync

import com.planlock.app.api.dto.ScheduleUpsertRequest
import com.planlock.app.api.dto.SyncScheduleView
import org.junit.Assert.*
import org.junit.Test

class ScheduleResponseLossEqualityTest {
    private fun request(clock: String = "10:00") = ScheduleUpsertRequest("schedule","2026-10-09",clock,"2026-10-09","11:00",repeatRule="{\"type\":\"daily\"}")
    private fun remote(clock: String = "10:00:00") = SyncScheduleView(id=51,title="schedule",startDate="2026-10-09",startTime=clock,endDate="2026-10-09",endTime="11:00:00",repeatRule="{\"type\":\"daily\"}")
    @Test fun mysqlTimeFormattingStillConfirmsPreviouslyCommittedUpdate() {
        assertTrue(sameSchedule(request(),remote()))
    }
    @Test fun nonzeroSecondsAreARealRemoteDifference() {
        assertFalse(sameSchedule(request(),remote("10:00:01")))
    }
    @Test fun equivalentJsonRepeatDoesNotTrapLostResponseRetry() {
        assertTrue(sameSchedule(request("10:00:00").copy(endTime="11:00:00"),remote().copy(repeatRule="{ \"type\" : \"daily\" }")))
    }
}
