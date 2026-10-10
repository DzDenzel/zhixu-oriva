package com.planlock.app.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncState(val running: Boolean = false, val revision: Long = 0, val attempts: Long = 0, val error: String? = null)
class LocalChangesPendingException : Exception("部分计划改动尚未上传，已保留本机数据")

/** Process-owned policy; page entry is deliberately a no-op, not a network trigger. */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val synchronize: suspend () -> Unit,
) {
    private val monitor = Any()
    private var coldStarted = false
    private var active: Job? = null
    private val mutableState = MutableStateFlow(SyncState())
    val state = mutableState.asStateFlow()

    fun coldStart(): Job? = synchronized(monitor) {
        if (coldStarted) null else { coldStarted = true; refresh() }
    }
    fun pageEntered() = Unit
    fun foregrounded(): Job = refresh()
    fun refresh(): Job = synchronized(monitor) {
        active?.takeIf { !it.isCompleted }?.let { return@synchronized it }
        mutableState.value = mutableState.value.copy(running = true, error = null, attempts = mutableState.value.attempts + 1)
        scope.launch(start = CoroutineStart.LAZY) {
            var failure: String? = null
            try { synchronize() }
            catch (pending: LocalChangesPendingException) { failure = pending.message }
            catch (cancelled: CancellationException) { failure = "同步已中断，请重试"; throw cancelled }
            catch (_: Exception) { failure = "同步失败，已保留本机数据；可下拉重试" }
            finally {
                synchronized(monitor) {
                    mutableState.value = mutableState.value.copy(running = false, revision = mutableState.value.revision + 1, error = failure)
                    active = null
                }
            }
        }.also { active = it; it.start() }
    }
}
