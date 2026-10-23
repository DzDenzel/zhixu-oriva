package com.planlock.app.record

import com.planlock.app.api.dto.DailyStat
import com.planlock.app.api.dto.EarlyEnding
import com.planlock.app.api.dto.ExecutionRecordDto
import com.planlock.app.api.dto.PlanExecutionStat
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.UnlockRecordDto
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Execution projection of RecordService.stats; inputs must already belong to the current history scope. */
object LocalExecutionStats {
    fun aggregate(
        executions: List<ExecutionRecordDto>,
        unlocks: List<UnlockRecordDto>,
        period: String?,
        today: OccurrenceDate,
    ): StatsResponse {
        val normalizedPeriod = when (period?.lowercase(Locale.ROOT)) {
            "day" -> "day"
            "month" -> "month"
            else -> "week"
        }
        val start = when (normalizedPeriod) {
            "day" -> today
            "month" -> today.plusDays(1 - today.day)
            else -> today.plusDays(-6)
        }.iso()
        val end = today.iso()
        val terminal = uniqueExecutions(executions).filter {
            it.status in listOf(1, 2) && it.planDate?.let { date -> date >= start && date <= end } == true
        }
        val completed = terminal.filter { it.status == 1 }
        val perDay = completed.groupingBy { requireNotNull(it.planDate) }.eachCount()
        var continuousDays = 0
        var cursor = if (end in perDay) today else today.previous()
        // Intentionally bounded by the selected period, as on the server.
        while (cursor.iso() in perDay) {
            continuousDays++
            cursor = cursor.previous()
        }
        val rangeStart = requireNotNull(timestampMicros("${start}T00:00:00"))
        val rangeEnd = requireNotNull(timestampMicros("${today.plusDays(1).iso()}T00:00:00"))
        val early = unlocks.withIndex().distinctBy { (index, row) -> identity(row.clientId, row.id, index) }
            .mapNotNull { (_, row) -> timestampMicros(row.unlockAt)?.let { row to it } }
            // SQL uses BETWEEN: preserve its inclusive midnight upper bound for E8 parity.
            .filter { (_, at) -> at in rangeStart..rangeEnd }
            .sortedByDescending { (_, at) -> at }
        return StatsResponse(
            period = normalizedPeriod,
            completedCount = completed.size,
            continuousDays = continuousDays,
            daily = perDay.toSortedMap().map { (date, count) -> DailyStat(date, count) },
            planExecution = terminal.groupBy { it.planId }.toSortedMap().map { (planId, values) ->
                PlanExecutionStat(
                    planId = planId,
                    completedCount = values.count { it.status == 1 },
                    earlyEndedCount = values.count { it.status == 2 },
                    // Sum before conversion, matching SUM(TIMESTAMPDIFF(MICROSECOND, ...)/1000).
                    actualDurationMs = values.sumOf { row ->
                        val from = timestampMicros(row.startedAt)
                        val to = timestampMicros(row.endedAt)
                        if (from == null || to == null) 0L else to - from
                    } / 1_000,
                )
            },
            unlockCount = early.size,
            earlyEndings = early.take(50).map { (row, _) -> EarlyEnding(row.unlockAt, row.planId, row.method) },
        )
    }

    /** Start edges are history evidence, not entries in the existing today-detail presentation. */
    fun todayExecutions(executions: List<ExecutionRecordDto>, today: OccurrenceDate): List<ExecutionRecordDto> =
        uniqueExecutions(executions).filter { it.planDate == today.iso() && it.status != 0 }.sortedByDescending { it.id }

    /** A transition day counts; following disabled days do not. Missing legacy logs mean always enabled. */
    fun expectedCount(plan: PlanSnapshotEntity, dates: List<OccurrenceDate>): Int {
        val log = runCatching {
            (Json.parseToJsonElement(plan.statusLog.orEmpty()) as? JsonArray).orEmpty().mapNotNull { item ->
                val entry = item as? JsonObject ?: return@mapNotNull null
                val date = (entry["date"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val status = (entry["status"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                if (date.length != 10 || OccurrenceDate.parseIso(date) == null || status !in 0..1) null else date to status
            }.sortedBy { it.first }
        }.getOrDefault(emptyList())
        return dates.count { date ->
            val key = date.iso()
            val enabled = log.any { it.first == key } || log.lastOrNull { it.first < key }?.second != 0
            enabled && PlanOccurrence.occursOn(plan, date)
        }
    }

    private fun uniqueExecutions(values: List<ExecutionRecordDto>) = values.withIndex()
        .distinctBy { (index, row) -> identity(row.clientId, row.id, index) }.map { it.value }

    private fun identity(clientId: String?, id: Long, index: Int): String = when {
        !clientId.isNullOrBlank() -> "client:$clientId"
        id != 0L -> "server:$id"
        else -> "unidentified:$index"
    }

    private fun OccurrenceDate.iso() = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)

    private val timestamp = Regex("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?$")

    // UTC is a neutral arithmetic frame for timezone-less LocalDateTime values, matching MySQL.
    // Calendar-era APIs keep this projection usable on minSdk 24 without introducing desugaring.
    private fun timestampMicros(value: String?): Long? = runCatching {
        val match = timestamp.matchEntire(value ?: return null) ?: return null
        val whole = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse("${match.groupValues[1]}T${match.groupValues[2]}") ?: return null
        whole.time * 1_000 + match.groupValues[3].take(6).padEnd(6, '0').toLong()
    }.getOrNull()
}
