package com.planlock.app

import com.planlock.app.record.entity.PlanSnapshotEntity
import java.util.Locale

internal data class PlanNotificationText(val title: String, val body: String)

/** Presentation only; scheduling, scope ownership and delivery remain with the caller. */
internal object PlanNotificationCopy {
    const val ongoing = "正在按计划进行"

    fun reminder(plan: PlanSnapshotEntity, typeName: String): PlanNotificationText {
        fun minutes(value: String?): Int = value.orEmpty().split(':').let {
            (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        fun clock(value: String?): String = minutes(value).let { String.format(Locale.ROOT, "%02d:%02d", it / 60, it % 60) }
        val overnight = if (minutes(plan.endTime) < minutes(plan.startTime)) "次日 " else ""
        return PlanNotificationText("「${plan.name}」${plan.remindAdvanceMin ?: 0} 分钟后开始",
            "${clock(plan.startTime)} – $overnight${clock(plan.endTime)} · $typeName")
    }

    fun completed(name: String, durationMillis: Long) =
        PlanNotificationText("「$name」已完成", "专注 ${durationLabel(durationMillis)}")
}

internal fun durationLabel(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when { minutes < 1 -> "不足 1 分钟"; minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0L -> "${minutes / 60} 小时"; else -> "${minutes / 60} 小时 ${minutes % 60} 分" }
}
