package com.planlock.app

import android.content.Context
import com.planlock.app.api.ReminderReadiness
import com.planlock.app.record.PlanAlarmDeliveryLog
import com.planlock.app.record.PlanCalendarFallback
import com.planlock.app.security.probeReminderReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI refreshes this snapshot on resume; probing itself never changes system policy. */
object ReminderReadinessReader {
    suspend fun read(context: Context): ReminderReadiness = withContext(Dispatchers.IO) {
        probeReminderReadiness(context.applicationContext, PlanCalendarFallback(context).isEnabled(), PlanAlarmDeliveryLog(context).lastDeferral())
    }
}
