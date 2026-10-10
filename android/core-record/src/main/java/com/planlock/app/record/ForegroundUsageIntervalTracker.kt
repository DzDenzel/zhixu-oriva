package com.planlock.app.record

/** 数据来源等级；降级源只能表达近似区间，报告必须显式展示。 */
enum class ObservationSource {
    ACCESSIBILITY,
    USAGE_STATS_APPROXIMATE,
}

data class ForegroundObservation(
    val planId: Long,
    val packageName: String,
    val appName: String?,
    val shouldTrack: Boolean,
    val source: ObservationSource,
)

data class ForegroundUsageInterval(
    val planId: Long,
    val packageName: String,
    val appName: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMillis: Long,
    val source: ObservationSource,
)

/** 把高频前台事件合并为完整使用区间；开放区间不会成为上传记录。 */
class ForegroundUsageIntervalTracker {
    private data class OpenInterval(
        val planId: Long,
        val packageName: String,
        var appName: String?,
        val startedAtMillis: Long,
        val source: ObservationSource,
    )

    private var current: OpenInterval? = null

    fun observe(observation: ForegroundObservation, atMillis: Long): ForegroundUsageInterval? {
        val active = current
        val continues = observation.shouldTrack &&
            active?.planId == observation.planId &&
            active.packageName == observation.packageName &&
            active.source == observation.source
        if (continues) {
            if (!observation.appName.isNullOrBlank()) active?.appName = observation.appName
            return null
        }

        val closed = close(atMillis)
        if (observation.shouldTrack) {
            current = OpenInterval(
                planId = observation.planId,
                packageName = observation.packageName,
                appName = observation.appName,
                startedAtMillis = atMillis,
                source = observation.source,
            )
        }
        return closed
    }

    fun close(atMillis: Long): ForegroundUsageInterval? {
        val active = current ?: return null
        current = null
        val endedAt = atMillis.coerceAtLeast(active.startedAtMillis)
        return ForegroundUsageInterval(
            planId = active.planId,
            packageName = active.packageName,
            appName = active.appName,
            startedAtMillis = active.startedAtMillis,
            endedAtMillis = endedAt,
            durationMillis = endedAt - active.startedAtMillis,
            source = active.source,
        )
    }

    fun active(): ForegroundObservation? = current?.let {
        ForegroundObservation(it.planId, it.packageName, it.appName, true, it.source)
    }
}
