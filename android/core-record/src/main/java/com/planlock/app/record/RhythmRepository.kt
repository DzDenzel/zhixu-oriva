package com.planlock.app.record

import android.content.Context
import com.planlock.app.api.ConnectionStore
import com.planlock.app.api.PlanTypeDefinition
import com.planlock.app.api.TokenStore
import com.planlock.app.api.dto.RhythmSource
import com.planlock.app.api.dto.RhythmSummary
import java.util.Calendar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RhythmState(
    val dataScope: String = "",
    val summary: RhythmSummary? = null,
    val customPlanTypes: List<PlanTypeDefinition> = emptyList(),
    val error: String? = null,
)

/** Reads one committed local snapshot. This path does not construct an API client or wait for sync. */
class RhythmRepository(private val store: RecordStore) {
    constructor(context: Context) : this(RecordStore(context))

    suspend fun snapshot(scope: String, today: OccurrenceDate): RhythmState = withContext(Dispatchers.IO) {
        val owned = store.forScope(scope)
        owned.scopedTransaction {
            val executions = owned.executionHistory()
            val behavior = owned.behaviorHistory()
            val cursor = owned.historyCursor()
            val pending = owned.pending()
            val synchronized = (cursor.executionId > 0 || cursor.behaviorId > 0 || cursor.unlockId > 0) &&
                pending.none { it.type in setOf("execution", "violation", "unlock", "plan_upsert", "plan_delete") }
            RhythmState(scope, RhythmStats.aggregate(owned.plans(), executions, behavior, today,
                source = if (synchronized) RhythmSource.SYNCED else RhythmSource.LOCAL), owned.planTypes())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<RhythmState> = combine(TokenStore.state, ConnectionStore.state) { auth, connection ->
        visibleHistoryScope(auth, connection.instanceId)
    }.distinctUntilChanged().flatMapLatest { scope ->
        combine(store.localRevision, localDates()) { _, date -> date }.mapLatest { date ->
            try { snapshot(scope, date) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RhythmState(scope, error = "本机统计读取失败，请重试") }
        }.onStart { emit(RhythmState(dataScope = scope)) }
    }.flowOn(Dispatchers.IO)

    private fun localDates(): Flow<OccurrenceDate> = flow {
        while (currentCoroutineContext().isActive) {
            emit(OccurrenceDate.from(Calendar.getInstance()))
            delay(60_000)
        }
    }.distinctUntilChanged()
}

/** The UI owns the coroutine scope (rememberCoroutineScope or Android ViewModel.viewModelScope). */
class RhythmViewModel(store: RecordStore, scope: CoroutineScope) {
    val state: StateFlow<RhythmState> = RhythmRepository(store).observe()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), RhythmState())
}
