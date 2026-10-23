package com.planlock.app.record

import com.planlock.app.api.dto.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Pure REQ-042 projection; the legacy period statistics deliberately keep their previous contract. */
object RhythmStats {
    fun aggregate(
        plans: List<PlanSnapshotEntity>,
        executions: List<ExecutionRecordDto>,
        behavior: List<RhythmBehavior>,
        today: OccurrenceDate,
        days: Int = 62,
        source: RhythmSource = RhythmSource.LOCAL,
    ): RhythmSummary {
        require(days in 1..366) { "days must be between 1 and 366" }
        val end = today.iso()
        val currentStart = today.plusDays(-29).iso()
        val previousStart = today.plusDays(-59).iso()
        val recentStart = today.plusDays(-6).iso()
        val previousWeekStart = today.plusDays(-13).iso()
        val rows = executions.withIndex().distinctBy { (i, row) -> identity(row.clientId, row.id, i) }
            .map { it.value }.filter { row -> row.planDate?.let { it <= end } == true && row.status in listOf(1, 2, 3) }
        val grouped = rows.groupBy { it.planId to it.planDate }
        val byId = plans.associateBy { it.id }
        val logs = plans.associate { it.id to statusChanges(it.statusLog) }
        val ids = (byId.keys + rows.map { it.planId }).sorted()
        fun expected(id: Long, date: OccurrenceDate, key: String): Int = byId[id]?.let { plan ->
            val log = logs[id].orEmpty()
            val enabled = log.any { it.first == key } || log.lastOrNull { it.first < key }?.second != 0
            if (enabled && PlanOccurrence.occursOn(plan, date)) 1 else 0
        } ?: 0
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val duration = rows.filter { it.status in listOf(1, 2) && it.planDate!! >= previousStart }.associateWith { row ->
            val from = micros(row.startedAt, formatter)
            val to = micros(row.endedAt, formatter)
            if (from == null || to == null) 0L else (to - from).coerceAtLeast(0)
        }
        val count = mutableMapOf<Pair<Long, String>, Counts>()
        // A constant 60-day expansion serves both current and previous metrics; history outside it
        // is consulted only for the streak, avoiding a plan x full-history expansion.
        for (offset in -maxOf(59, days - 1)..0) {
            val date = today.plusDays(offset)
            val key = date.iso()
            ids.forEach { id ->
                val observed = grouped[id to key].orEmpty()
                val completed = observed.count { it.status == 1 }
                val early = observed.count { it.status == 2 }
                val excluded = observed.count { it.status == 3 }
                count[id to key] = Counts(completed, early, (expected(id, date, key) - completed - early - excluded).coerceAtLeast(0),
                    observed.sumOf { duration[it] ?: 0L })
            }
        }
        fun values(from: String, until: String) = count.filterKeys { it.second >= from && it.second < until }.values
        fun ratio(from: String, until: String): Ratio = values(from, until).let { v -> Ratio(v.sumOf { it.completed }, v.sumOf { it.total }) }
        val tomorrow = today.plusDays(1).iso()
        val week = ratio(recentStart, tomorrow)
        val prevWeek = ratio(previousWeekStart, recentStart)
        val perDay = count.entries.groupBy { it.key.second }.mapValues { (_, entries) -> entries.map { it.value } }
        // A successful day has at least one completed occurrence and no early/missed occurrence.
        val successful = rows.filter { it.status == 1 }.mapNotNull { it.planDate }.toSet().filterTo(mutableSetOf()) { date ->
            val counts = perDay[date]
            if (counts != null) counts.sumOf { it.early + it.missed } == 0
            else {
                val oldDate = OccurrenceDate.parseIso(date)
                oldDate != null && ids.all { id ->
                    val observed = grouped[id to date].orEmpty()
                    observed.none { it.status == 2 } &&
                        expected(id, oldDate, date) <= observed.count { it.status == 1 || it.status == 3 }
                }
            }
        }
        var cursor = if (end in successful) today else today.previous()
        var streak = 0
        while (cursor.iso() in successful) { streak++; cursor = cursor.previous() }
        val calendar = (-(days - 1)..0).map { offset ->
            val date = today.plusDays(offset).iso()
            val v = perDay[date].orEmpty()
            DayCell(date, v.sumOf { it.total }, v.sumOf { it.completed }, v.sumOf { it.early },
                v.sumOf { it.missed }, minutes(v.sumOf { it.micros }), offset == 0)
        }
        val performance = ids.mapNotNull { id ->
            val v = count.filterKeys { it.first == id && it.second >= currentStart }.values
            val completed = v.sumOf { it.completed }
            val total = v.sumOf { it.total }
            if (total == 0) null else PlanPerformance(id, byId[id]?.name ?: "已删除计划", byId[id]?.type.orEmpty(), Ratio(completed, total), completed, total)
        }.sortedWith { a, b ->
            val comparison = (a.completed.toLong() * b.total).compareTo(b.completed.toLong() * a.total)
            if (comparison != 0) comparison else a.planId.compareTo(b.planId)
        }
        val change = week.completed - prevWeek.completed
        val hasHistory = rows.any { it.status in listOf(1, 2) }
        val headline = if (!hasHistory) "还没有执行记录，开始一个计划后这里会出现你的节奏。"
            else "近7天完成${week.completed}次，比上一周期${if (change > 0) "多${change}次" else if (change < 0) "少${-change}次" else "持平"}；连续达成${streak}天。"
        return RhythmSummary(headline, streak, week, prevWeek,
            minutes(values(currentStart, tomorrow).sumOf { it.micros }), minutes(values(previousStart, currentStart).sumOf { it.micros }),
            values(currentStart, tomorrow).sumOf { it.early }, values(previousStart, currentStart).sumOf { it.early },
            calendar, performance, distraction(behavior, recentStart, tomorrow), source)
    }

    private data class Counts(val completed: Int, val early: Int, val missed: Int, val micros: Long) {
        val total get() = completed + early + missed
    }

    private fun distraction(rows: List<RhythmBehavior>, start: String, end: String): Distraction? {
        val closed = rows.withIndex().distinctBy { (i, r) -> identity(r.clientId, r.id, i) }.map { it.value }
            .filter {
                val from = it.startedAt
                val to = it.endedAt
                val duration = it.durationMs
                !com.planlock.app.api.HostApplication.owns(it.packageName) &&
                    from != null && from >= "${start}T00:00:00" && from < "${end}T00:00:00" &&
                    to != null && to >= from && duration != null && duration >= 0
            }
        if (closed.isEmpty()) return null
        val groups = closed.groupBy { it.packageName?.takeIf(String::isNotBlank) ?: "unknown" }
        val sources = groups.map { (pkg, values) ->
            val latest = values.sortedWith(compareByDescending<RhythmBehavior> { it.startedAt }.thenByDescending { it.id }).first()
            Triple(pkg, latest.appName?.takeIf(String::isNotBlank) ?: pkg, values.sumOf { it.durationMs ?: 0 })
        }.sortedWith(compareByDescending<Triple<String, String, Long>> { it.third }.thenBy { it.first })
        return Distraction((closed.sumOf { it.durationMs ?: 0 } / 60_000).toInt(),
            sources.take(3).map { DistractionSource(it.second, (it.third / 60_000).toInt()) })
    }

    private fun statusChanges(raw: String?): List<Pair<String, Int>> = runCatching {
        Json.parseToJsonElement(raw ?: "[]").jsonArray.mapNotNull { value ->
            val date = value.jsonObject["date"]?.jsonPrimitive?.content
            val status = value.jsonObject["status"]?.jsonPrimitive?.intOrNull
            if (date != null && date.length == 10 && OccurrenceDate.parseIso(date) != null && status in 0..1) date to requireNotNull(status) else null
        }.sortedBy { it.first }
    }.getOrDefault(emptyList())

    private fun identity(clientId: String?, id: Long, index: Int): String = when {
        !clientId.isNullOrBlank() -> "client:$clientId"
        id != 0L -> "server:$id"
        else -> "unidentified:$index"
    }
    private val timestamp = Regex("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?$")
    private fun micros(value: String?, formatter: SimpleDateFormat): Long? = runCatching {
        val match = timestamp.matchEntire(value ?: return null) ?: return null
        val parsed = formatter.parse("${match.groupValues[1]}T${match.groupValues[2]}") ?: return null
        parsed.time * 1_000 + match.groupValues[3].take(6).padEnd(6, '0').toLong()
    }.getOrNull()
    private fun minutes(micros: Long) = (micros / 60_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun OccurrenceDate.iso() = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)
}
