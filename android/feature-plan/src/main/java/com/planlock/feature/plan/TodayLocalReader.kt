package com.planlock.feature.plan

import android.content.Context
import com.planlock.app.api.WeatherSnapshot
import com.planlock.app.api.WeatherStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.sync.AppSync
import com.planlock.app.sync.SyncOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TodayLocalData(
    val plans: List<PlanSnapshotEntity>,
    val schedules: List<ScheduleSnapshotEntity>,
    val violations: Int,
    val unlocks: Int,
    val streak: Int,
    val weather: WeatherSnapshot?,
)

data class TodayLocalRead(val data: TodayLocalData?, val error: String?)

class TodayLocalReader(
    private val plans: suspend () -> List<PlanSnapshotEntity>,
    private val schedules: suspend () -> List<ScheduleSnapshotEntity>,
    private val overview: suspend (Long?) -> SyncOverview,
    private val weather: suspend () -> WeatherSnapshot?,
) {
    /** Shared disk-only path for cold preload and every subsequent page refresh. */
    suspend fun read(userId: Long?, todayKey: String): TodayLocalRead = withContext(Dispatchers.IO) {
        try {
            val localPlans = plans()
            val localSchedules = schedules()
            val cached = overview(userId)
            val today = cached.today?.takeIf { it.date == todayKey }
            TodayLocalRead(
                data = TodayLocalData(
                    plans = localPlans,
                    schedules = localSchedules,
                    violations = today?.violationCount ?: 0,
                    unlocks = today?.unlockCount ?: 0,
                    streak = cached.stats?.continuousDays ?: 0,
                    weather = weather(),
                ),
                error = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TodayLocalRead(data = null, error = "本机数据读取失败，请重试")
        }
    }

    companion object {
        fun create(context: Context): TodayLocalReader {
            val app = context.applicationContext
            val store = RecordStore(app)
            val cache = AppSync.get(app).cache
            val weather = WeatherStore(app.planLockPreferences)
            return TodayLocalReader(store::plans, store::schedules, cache::read, weather::cachedSnapshot)
        }
    }
}
