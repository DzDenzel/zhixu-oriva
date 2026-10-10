package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class DayTimelineTest {
    private fun plan(id:Long,start:String,end:String,rule:String?="{\"type\":\"daily\"}")=PlanSnapshotEntity(id,1,"专注$id","学习",start,end,rule,null,null,15,null,1,0)
    private fun schedule(id:Long,startDate:String,start:String?,endDate:String,end:String?,allDay:Boolean=false,rule:String?=null)=ScheduleSnapshotEntity(id,1,"安排$id",startDate,start,endDate,end,allDay,"会议室",null,null,rule,null,false,"Asia/Shanghai",1,0)

    @Test fun mergesPlansAndSchedulesWithoutChangingPlanSet(){
        val entries=DayTimeline.build("2026-09-23",listOf(plan(1,"09:00","10:00")),listOf(schedule(2,"2026-09-23","09:30","2026-09-23","11:00")),9*60+40)
        assertEquals(setOf(TimelineKind.PLAN,TimelineKind.SCHEDULE),entries.map{it.kind}.toSet())
        assertEquals(TimelineState.RUNNING,entries.first{it.kind==TimelineKind.PLAN}.state)
    }

    @Test fun clipsCrossMidnightScheduleOnBothDays(){
        val s=schedule(2,"2026-09-23","23:00","2026-09-24","01:00")
        assertEquals(1380,DayTimeline.build("2026-09-23",emptyList(),listOf(s),0).single().startMin)
        val tail=DayTimeline.build("2026-09-24",emptyList(),listOf(s),30).single()
        assertEquals(0,tail.startMin);assertEquals(60,tail.endMin);assertTrue(tail.clippedStart)
    }

    @Test fun allDayClosedIntervalAndTimezoneIndependent(){
        val s=schedule(2,"2026-09-23",null,"2026-09-24",null,true)
        val old=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT")); assertEquals(1,DayTimeline.build("2026-09-24",emptyList(),listOf(s),0).size)
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00")); assertEquals(1,DayTimeline.build("2026-09-24",emptyList(),listOf(s),0).size)
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun overlapShowsTwoColumnsAndOverflowCount(){
        val schedules=(1L..4L).map{schedule(it,"2026-09-23","09:00","2026-09-23","10:00")}
        val entries=DayTimeline.build("2026-09-23",emptyList(),schedules,0)
        assertEquals(2,entries.size);assertEquals(2,entries.last().overflowCount);assertEquals(2,entries.last().columnCount)
    }

    @Test fun listModePreservesEveryOverlappingSchedule(){
        val schedules=(1L..4L).map{schedule(it,"2026-09-23","09:00","2026-09-23","10:00")}
        val entries=DayTimeline.build("2026-09-23",emptyList(),schedules,0,collapseOverlaps=false)
        assertEquals(listOf(1L,2L,3L,4L),entries.map{it.id})
        assertTrue(entries.all { it.columnCount == 1 && it.overflowCount == 0 })
    }

    @Test fun rejectsUnsupportedRuleAtInputBoundary(){
        assertFalse(PlanOccurrence.isSupportedRule("{\"type\":\"monthly\"}"))
        assertTrue(PlanOccurrence.isSupportedRule("{\"type\":\"weekly\",\"weekdays\":[1,3]}"))
    }
}
