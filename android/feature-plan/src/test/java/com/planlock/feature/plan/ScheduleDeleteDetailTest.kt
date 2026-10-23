package com.planlock.feature.plan

import com.planlock.app.record.entity.ScheduleSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleDeleteDetailTest {
    private fun schedule(rule: String?, remind: Int?) = ScheduleSnapshotEntity(1, 1, "某人生日", "2026-10-15", null, "2026-10-15", null,
        true, null, null, remind, rule, null, tzId = null, status = 1, version = 1)

    @Test fun oneOffWithoutReminder() =
        assertEquals("这条日程会被删除。删除后不能撤销。", scheduleDeleteDetail(schedule(null, null)))

    @Test fun repeatingWithReminder() =
        assertEquals("这是一条重复日程，每一次都会一起删除，它的提醒也会取消。删除后不能撤销。",
            scheduleDeleteDetail(schedule("""{"type":"yearly"}""", 10)))
}
