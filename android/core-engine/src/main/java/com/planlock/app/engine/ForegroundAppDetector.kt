package com.planlock.app.engine

import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * 使用情况访问权限识别最近前台应用（辅助识别；主拦截走无障碍服务）。
 */
class ForegroundAppDetector(private val context: Context) {

    fun currentForegroundPackage(): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 60_000L
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
            .filter { it.lastTimeUsed > 0 }
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }
}
