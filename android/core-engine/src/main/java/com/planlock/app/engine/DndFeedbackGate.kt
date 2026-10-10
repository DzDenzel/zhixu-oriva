package com.planlock.app.engine

class DndFeedbackGate(private val intervalMillis: Long) {
    private var lastPlanId: Long? = null
    private var lastEmittedAt = Long.MIN_VALUE

    fun shouldEmit(planId: Long, nowMillis: Long): Boolean {
        val planChanged = lastPlanId != planId
        val intervalElapsed = nowMillis - lastEmittedAt >= intervalMillis
        if (!planChanged && !intervalElapsed) return false
        lastPlanId = planId
        lastEmittedAt = nowMillis
        return true
    }
}
