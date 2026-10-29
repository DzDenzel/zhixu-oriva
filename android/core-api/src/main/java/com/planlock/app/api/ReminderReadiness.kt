package com.planlock.app.api

import android.content.Intent

enum class ReadinessKey {
    NOTIFICATIONS,
    EXACT_ALARM,
    FULL_SCREEN,
    PROMOTED,
    VENDOR_NOTIFICATION,
    BACKGROUND,
    PUNCTUAL_START,
    CALENDAR_FALLBACK,
}

enum class ReadinessState { ON, OFF, UNKNOWN }

data class ReminderReadinessItem(
    val key: ReadinessKey,
    val state: ReadinessState,
    val settingsIntent: Intent?,
)

enum class AlarmKind { REMINDER, START, END }

data class AlarmDeferral(
    val kind: AlarmKind,
    val planId: Long,
    val planName: String,
    val scheduledAt: Long,
    val deliveredAt: Long,
) {
    val lateMs: Long get() = deliveredAt - scheduledAt
}

data class ReminderReadiness(
    val items: List<ReminderReadinessItem>,
    val lastDeferral: AlarmDeferral?,
)
