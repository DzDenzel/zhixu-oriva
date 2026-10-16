package com.planlock.app.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncState(val running: Boolean = false, val revision: Long = 0, val attempts: Long = 0, val error: String? = null, val loginRequired: Boolean = false)
class LocalChangesPendingException : Exception("部分计划改动尚未上传，已保留本机数据")

/** Process-owned policy; page entry is deliberately a no-op, not a network trigger. */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val synchronize: suspend () -> Unit,
) {
    private val monitor = Any()
    private var coldStarted = false
    private var active: Job? = null
    private var feedbackGeneration = 0L
    private val mutableState = MutableStateFlow(SyncState())
    val state = mutableState.asStateFlow()

    fun coldStart(): Job? = synchronized(monitor) {
        if (coldStarted) null else { coldStarted = true; refresh() }
    }
    fun pageEntered() = Unit
    /** Explicit local-only mode dismisses obsolete feedback without requesting a transfer. */
    fun clearError() = synchronized(monitor) {
        feedbackGeneration++
        mutableState.value = mutableState.value.copy(error = null, loginRequired = false)
    }
    fun foregrounded(): Job = refresh()
    fun refresh(): Job = synchronized(monitor) {
        active?.takeIf { !it.isCompleted }?.let { return@synchronized it }
        val generation = feedbackGeneration
        mutableState.value = mutableState.value.copy(running = true, error = null, attempts = mutableState.value.attempts + 1)
        scope.launch(start = CoroutineStart.LAZY) {
            var failure: String? = null
            var loginRequired = false
            try { synchronize() }
            catch (_: OwnerLoginRequiredException) { loginRequired = true }
            catch (pending: LocalChangesPendingException) { failure = pending.message }
            catch (cancelled: CancellationException) { failure = "同步已中断，请重试"; throw cancelled }
            catch (_: Exception) { failure = "同步失败，已保留本机数据；可下拉重试" }
            finally {
                synchronized(monitor) {
                    val current = mutableState.value
                    // A connection change can invalidate feedback after the session lock is released.
                    val feedbackIsCurrent = generation == feedbackGeneration
                    mutableState.value = current.copy(
                        running = false, revision = current.revision + 1,
                        error = if (feedbackIsCurrent) failure else current.error,
                        loginRequired = if (feedbackIsCurrent) loginRequired else current.loginRequired,
                    )
                    active = null
                }
            }
        }.also { active = it; it.start() }
    }
}
