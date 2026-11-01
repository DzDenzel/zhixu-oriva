package com.planlock.app

import android.content.Context
import com.planlock.app.api.ReadinessKey
import com.planlock.app.api.ReadinessState
import com.planlock.app.record.RecordStore
import kotlinx.coroutines.Dispatchers
import androidx.core.content.edit
import kotlinx.coroutines.withContext

/**
 * REQ-044 U-R5：第一次出现带提醒的计划时打开一次提醒引导；只在确有缺项（任一项未开启，或后台运行未确认开启——
 * ColorOS 默认「智能优化」正是 S0 证实的推迟来源）时打开，打开过就不再自动出现
 * （之后从 设置 › 权限 › 提醒检查 或推迟告知的「去设置」进入）。设备级记录，不随账号同步。
 */
internal object ReminderGuideOnce {
    private const val PREFS = "reminder_guide"
    private const val SHOWN = "auto_shown"

    suspend fun shouldShow(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SHOWN, false)) return@withContext false
        if (RecordStore(app).plans().none { it.remindAdvanceMin != null }) return@withContext false
        val items = ReminderReadinessReader.read(app).items
        val needsGuide = items.any { it.state == ReadinessState.OFF } ||
            items.any { it.key == ReadinessKey.BACKGROUND && it.state != ReadinessState.ON }
        prefs.edit { putBoolean(SHOWN, true) }
        needsGuide
    }
}
