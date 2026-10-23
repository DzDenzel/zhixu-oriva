package com.planlock.app.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SyncPhase { IDLE, BACKGROUND, USER, FAILED, LOGIN_REQUIRED }
data class SyncState(val running: Boolean = false, val revision: Long = 0, val attempts: Long = 0, val error: String? = null, val loginRequired: Boolean = false, val backgroundRunning: Boolean = false) {
    val phase: SyncPhase get() = when {
        running -> SyncPhase.USER
        backgroundRunning -> SyncPhase.BACKGROUND
        loginRequired -> SyncPhase.LOGIN_REQUIRED
        error != null -> SyncPhase.FAILED
        else -> SyncPhase.IDLE
    }
}
class LocalChangesPendingException : Exception("部分计划改动尚未上传，已保留本机数据")

/** Process-owned policy; page entry is deliberately a no-op, not a network trigger. */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 20_000,
    private val synchronize: suspend () -> Unit,
) {
    private val monitor = Any()
    private var coldStarted = false
    private var active: Job? = null
    private var feedbackGeneration = 0L
    private val mutableState = MutableStateFlow(SyncState())
    val state = mutableState.asStateFlow()

    fun coldStart(): Job? = synchronized(monitor) {
        if (coldStarted) null else { coldStarted = true; refresh(userInitiated = false) }
    }
    fun pageEntered() = Unit
    fun pendingFeedback(error: String?) = synchronized(monitor) {
        mutableState.value = mutableState.value.copy(error = error)
    }
    /** Explicit local-only mode dismisses obsolete feedback without requesting a transfer. */
    fun clearError() = synchronized(monitor) {
        feedbackGeneration++
        mutableState.value = mutableState.value.copy(error = null, loginRequired = false)
    }
    fun foregrounded(): Job = refresh(userInitiated = false)
    fun cancel() = synchronized(monitor) { active?.cancel() }
    fun refresh(userInitiated: Boolean = true): Job = synchronized(monitor) {
        active?.takeIf { !it.isCompleted }?.let {
            if (userInitiated) mutableState.value = mutableState.value.copy(running = true, backgroundRunning = false)
            return@synchronized it
        }
        val generation = feedbackGeneration
        mutableState.value = mutableState.value.copy(running = userInitiated, backgroundRunning = !userInitiated, error = null, attempts = mutableState.value.attempts + 1)
        scope.launch(start = CoroutineStart.LAZY) {
            var failure: String? = null
            var loginRequired = false
            try { withTimeout(timeoutMillis) { synchronize() } }
            catch (_: OwnerLoginRequiredException) { loginRequired = true }
            catch (pending: LocalChangesPendingException) { failure = pending.message }
            catch (_: TimeoutCancellationException) { failure = "同步超过 20 秒，已保留本机数据；可下拉重试" }
            catch (cancelled: CancellationException) { failure = "同步已中断，请重试"; throw cancelled }
            catch (_: Exception) { failure = "同步失败，已保留本机数据；可下拉重试" }
            finally {
                synchronized(monitor) {
                    val current = mutableState.value
                    // A connection change can invalidate feedback after the session lock is released.
                    val feedbackIsCurrent = generation == feedbackGeneration
                    mutableState.value = current.copy(
                        running = false, backgroundRunning = false, revision = current.revision + 1,
                        error = if (feedbackIsCurrent) failure else current.error,
                        loginRequired = if (feedbackIsCurrent) loginRequired else current.loginRequired,
                    )
                    active = null
                }
            }
        }.also { active = it; it.start() }
    }
}
