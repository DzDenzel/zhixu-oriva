package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class TimelineKind { PLAN, SCHEDULE }
enum class TimelineState { RUNNING, UPCOMING, ENDED, PAUSED }

data class TimelineEntry(
    val kind: TimelineKind,
    val id: Long,
    val title: String,
    val startMin: Int,
    val endMin: Int,
    val clippedStart: Boolean = false,
    val clippedEnd: Boolean = false,
    val state: TimelineState,
    val sourceLabel: String,
    val location: String? = null,
    val column: Int = 0,
    val columnCount: Int = 1,
    val overflowCount: Int = 0,
    val backgroundBand: Boolean = false,
    val originalStartMin: Int = startMin,
    val originalEndMin: Int = endMin,
    val occurrenceEnded: Boolean = false,
)

data class TimelineWindow(val startMin: Int, val endMin: Int) {
    val duration get() = endMin - startMin
}

/** Single read model for the Today canvas. Dates are ISO local calendar dates. */
object DayTimeline {
    /** Round outward to hours, pad by one hour, then extend forward (backward at midnight) to eight hours. */
    fun window(entries: List<TimelineEntry>, nowMinutes: Int): TimelineWindow {
        val now = nowMinutes.coerceIn(0, 1439)
        var start = ((minOf(entries.minOfOrNull { it.startMin } ?: now, now) - 60).coerceAtLeast(0) / 60) * 60
        var end = (((maxOf(entries.maxOfOrNull { it.endMin } ?: now, now) + 60 + 59) / 60) * 60).coerceAtMost(1440)
        end = maxOf(end, start + 480).coerceAtMost(1440)
        start = minOf(start, end - 480).coerceAtLeast(0)
        return TimelineWindow(start, end)
    }

    fun isBackgroundBand(entry: TimelineEntry, window: TimelineWindow): Boolean =
        (minOf(entry.endMin, window.endMin) - maxOf(entry.startMin, window.startMin)).coerceAtLeast(0) * 3 >= window.duration * 2

    fun rangeLabel(entry: TimelineEntry): String {
        if (entry.sourceLabel == "全天日程") return "全天"
        fun clock(min: Int) = "%02d:%02d".format(Locale.US, (min / 60) % 24, min % 60)
        val from = (if (entry.clippedStart) "← 昨日 " else "") + clock(entry.originalStartMin)
        val to = (if (entry.clippedEnd) "次日 " else "") + clock(entry.originalEndMin)
        return "$from → $to"
    }
    fun build(
        date: String,
        plans: List<PlanSnapshotEntity>,
        schedules: List<ScheduleSnapshotEntity>,
        nowMinutes: Int,
        collapseOverlaps: Boolean = true,
    ): List<TimelineEntry> {
        val calendarDate = OccurrenceDate.parseIso(date) ?: return emptyList()
        val previous = calendarDate.previous()
        val entries = buildList {
            plans.filter { it.status == 1 }.forEach { plan ->
                val start = minutes(plan.startTime) ?: return@forEach
                val end = minutes(plan.endTime) ?: return@forEach
                if (start == end) return@forEach
                if (PlanOccurrence.occursOn(plan, calendarDate)) {
                    val ended = PlanOccurrence.isEndedOn(plan, date)
                    val occurrenceState = if(ended) TimelineState.ENDED else state(plan.status,start,if(end>start) end else 1440,nowMinutes)
                    add(entry(TimelineKind.PLAN,plan.id,plan.name,start,if(end>start) end else 1440,false,end<=start,occurrenceState,"计划").copy(originalStartMin=start,originalEndMin=end,occurrenceEnded=ended))
                }
                if (end < start && PlanOccurrence.occursOn(plan.repeatRule, previous)) {
                    val previousKey="%04d-%02d-%02d".format(Locale.ROOT,previous.year,previous.month,previous.day)
                    val ended = PlanOccurrence.isEndedOn(plan, previousKey)
                    val occurrenceState=if(ended) TimelineState.ENDED else state(plan.status,0,end,nowMinutes)
                    add(entry(TimelineKind.PLAN,plan.id,plan.name,0,end,true,false,occurrenceState,"计划").copy(originalStartMin=start,originalEndMin=end,occurrenceEnded=ended))
                }
            }
            schedules.filter { it.status == 1 && occurs(it,date,calendarDate) }.forEach { schedule ->
                val range = visibleRange(schedule,date) ?: return@forEach
                add(entry(TimelineKind.SCHEDULE,schedule.id,schedule.title,range.first,range.second,range.first==0 && schedule.startDate<date,range.second==1440 && schedule.endDate>date,state(1,range.first,range.second,nowMinutes),if(schedule.allDay) "全天日程" else "日程",schedule.location).copy(originalStartMin=minutes(schedule.startTime)?:0,originalEndMin=minutes(schedule.endTime)?:1440))
            }
        }.sortedWith(compareBy<TimelineEntry> { it.startMin }.thenBy { it.endMin }.thenBy { it.kind })
        val window = window(entries, nowMinutes)
        val prepared = entries.map { it.copy(backgroundBand = isBackgroundBand(it, window)) }
        // 全天日程是当天背景，独立列在前面，不占用定时安排的重叠列或溢出名额。
        val (allDay, timed) = prepared.partition { it.kind == TimelineKind.SCHEDULE && it.sourceLabel == "全天日程" }
        return allDay + if (collapseOverlaps) layout(timed) else timed
    }

    private fun occurs(s: ScheduleSnapshotEntity,date:String,calendarDate:OccurrenceDate):Boolean {
        if (date < s.startDate) return false
        val repeated = !s.repeatRule.isNullOrBlank()
        return if (repeated) PlanOccurrence.occursOn(s.repeatRule,calendarDate) else date <= s.endDate
    }
    private fun visibleRange(s:ScheduleSnapshotEntity,date:String):Pair<Int,Int>? {
        if(s.allDay) return 0 to 1440
        val start=if(date==s.startDate) minutes(s.startTime) ?: return null else 0
        val end=if(date==s.endDate) minutes(s.endTime) ?: return null else 1440
        return if(end>start) start to end else null
    }
    private fun entry(kind:TimelineKind,id:Long,title:String,start:Int,end:Int,clipStart:Boolean,clipEnd:Boolean,state:TimelineState,label:String,location:String?=null)=TimelineEntry(kind,id,title,start,end,clipStart,clipEnd,state,label,location)
    private fun state(status:Int?,start:Int,end:Int,now:Int)=when { status!=1->TimelineState.PAUSED; now in start until end->TimelineState.RUNNING; now<start->TimelineState.UPCOMING; else->TimelineState.ENDED }
    private fun layout(items:List<TimelineEntry>):List<TimelineEntry> {
        val result=items.toMutableList(); var i=0
        while(i<result.size){ var j=i+1; var maxEnd=result[i].endMin; while(j<result.size && result[j].startMin<maxEnd){maxEnd=maxOf(maxEnd,result[j].endMin);j++}
            val group=result.subList(i,j).toList(); val shown=group.take(2); shown.forEachIndexed { index,item -> result[i+index]=item.copy(column=index,columnCount=minOf(2,group.size),overflowCount=if(index==1) maxOf(0,group.size-2) else 0) }; if(group.size>2) repeat(group.size-2){result.removeAt(i+2)}; i+=shown.size }
        return result
    }
    private fun minutes(value:String?):Int? { val p=value?.split(':')?:return null; val h=p.getOrNull(0)?.toIntOrNull()?:return null; val m=p.getOrNull(1)?.toIntOrNull()?:return null; return if(h in 0..23&&m in 0..59) h*60+m else null }
}
