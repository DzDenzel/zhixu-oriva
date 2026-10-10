package com.planlock.app.record

import com.planlock.app.record.entity.PendingRecordEntity

/** 行为明细双上限：默认最多 10,000 条且不超过 90 天，均按最旧优先清理。 */
class BehaviorRetentionPolicy(
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) {
    init {
        require(maxRows > 0)
        require(maxAgeMillis > 0)
    }

    fun idsToDelete(rows: List<PendingRecordEntity>, nowMillis: Long): List<Long> {
        val details = rows
            .asSequence()
            .filter { it.type == "violation" && it.startedAt != null && it.endedAt != null && it.durationMs != null }
            .sortedWith(compareBy<PendingRecordEntity> { it.createdAt }.thenBy { it.id })
            .toList()
        val expired = details.takeWhile { nowMillis - it.createdAt > maxAgeMillis }
        val retained = details.drop(expired.size)
        val overflowCount = (retained.size - maxRows).coerceAtLeast(0)
        return (expired + retained.take(overflowCount)).map { it.id }
    }

    companion object {
        const val DEFAULT_MAX_ROWS = 10_000
        const val DEFAULT_MAX_AGE_DAYS = 90L
        const val DEFAULT_MAX_AGE_MILLIS = DEFAULT_MAX_AGE_DAYS * 24 * 60 * 60 * 1000
    }
}
