package com.planlock.app.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class FeedbackKind { STARTED, PAUSED, COMPLETED, BLOCKED, DND_PERMISSION_MISSING }
data class ExecutionFeedbackEvent(val planId: Long, val kind: FeedbackKind)

/** 单次 UI 通知，不持有历史；没有界面订阅时不增加动画/拦截工作。 */
object ExecutionFeedback {
    private val mutableEvents = MutableSharedFlow<ExecutionFeedbackEvent>(extraBufferCapacity = 8)
    val events = mutableEvents.asSharedFlow()
    fun emit(planId: Long, kind: FeedbackKind) { mutableEvents.tryEmit(ExecutionFeedbackEvent(planId, kind)) }
}
