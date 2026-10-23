package com.planlock.feature.plan

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class TodayLocalKey(val userId: Long?, val dateKey: String, val scope: String = "")

enum class TodayLocalPhase { IDLE, READING, READY, TIMED_OUT }

data class TodayLocalState(
    val key: TodayLocalKey? = null,
    val read: TodayLocalRead? = null,
    val phase: TodayLocalPhase = TodayLocalPhase.IDLE,
)

/** Process-owned local state. It never retains an Activity or a composition scope. */
class TodayLocalStateHolder internal constructor(
    private val reader: TodayLocalReader,
    private val scope: CoroutineScope,
    private val preloadDeadlineMillis: Long = 1_200L,
    private val localChanges: StateFlow<Long> = MutableStateFlow(0L),
) {
    private val mutableState = MutableStateFlow(TodayLocalState())
    val state: StateFlow<TodayLocalState> = mutableState.asStateFlow()
    private var generation = 0L
    private var readJob: Job? = null
    private var queuedRefreshKey: TodayLocalKey? = null

    init {
        // Capture before launching: a commit between construction and collection must not be lost.
        var observed = localChanges.value
        scope.launch {
            localChanges.collect { revision ->
                if (revision != observed) {
                    observed = revision
                    refreshAfterLocalChange()
                }
            }
        }
    }

    @Synchronized
    private fun refreshAfterLocalChange() {
        mutableState.value.key?.let { start(it, force = true, deadlineMillis = null) }
    }

    /** Cold-start read. Observers may disappear without cancelling this process-owned job. */
    fun prefetch(key: TodayLocalKey) {
        start(key = key, force = false, deadlineMillis = preloadDeadlineMillis)
    }

    /** Page-owned refresh uses the baseline unbounded local read after preload fallback. */
    fun refresh(key: TodayLocalKey) {
        start(key = key, force = true, deadlineMillis = null)
    }

    @Synchronized
    private fun start(key: TodayLocalKey, force: Boolean, deadlineMillis: Long?) {
        val current = mutableState.value
        if (current.key == key) {
            if (current.phase == TodayLocalPhase.READING) {
                // A page refresh can arrive before the preload deadline. Retain it
                // so completion/timeout immediately hands off to baseline reading.
                if (force) queuedRefreshKey = key
                return
            }
            if (!force && current.phase != TodayLocalPhase.IDLE) return
        } else {
            readJob?.cancel()
            queuedRefreshKey = null
        }

        val previousRead = current.read.takeIf { current.key == key }
        val runGeneration = ++generation
        mutableState.value = TodayLocalState(key = key, read = previousRead, phase = TodayLocalPhase.READING)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = if (deadlineMillis == null) {
                reader.read(key.userId, key.dateKey, key.scope)
            } else {
                withTimeoutOrNull(deadlineMillis) { reader.read(key.userId, key.dateKey, key.scope) }
            }
            complete(runGeneration, key, previousRead, result)
        }
        readJob = job
        job.start()
    }

    @Synchronized
    private fun complete(
        runGeneration: Long,
        key: TodayLocalKey,
        previousRead: TodayLocalRead?,
        result: TodayLocalRead?,
    ) {
        if (generation != runGeneration || mutableState.value.key != key) return
        readJob = null
        mutableState.value = if (result == null) {
            TodayLocalState(key = key, read = previousRead, phase = TodayLocalPhase.TIMED_OUT)
        } else {
            val retained = if (result.data == null && previousRead?.data != null) {
                TodayLocalRead(data = previousRead.data, error = result.error)
            } else {
                result
            }
            TodayLocalState(key = key, read = retained, phase = TodayLocalPhase.READY)
        }
        if (queuedRefreshKey == key) {
            queuedRefreshKey = null
            start(key = key, force = true, deadlineMillis = null)
        }
    }

    companion object {
        @Volatile private var instance: TodayLocalStateHolder? = null

        fun get(context: Context): TodayLocalStateHolder = instance ?: synchronized(this) {
            instance ?: TodayLocalStateHolder(
                reader = TodayLocalReader.create(context.applicationContext),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                localChanges = com.planlock.app.record.RecordStore(context.applicationContext).localRevision,
            ).also { instance = it }
        }
    }
}
