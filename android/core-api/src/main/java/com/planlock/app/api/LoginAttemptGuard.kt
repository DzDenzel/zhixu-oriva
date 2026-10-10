package com.planlock.app.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One UI login attempt; dismissal/exit makes late responses ineligible for identity commit. */
class LoginAttemptGuard {
    private var generation = 0L
    private val mutableSubmitting = MutableStateFlow(false)
    val submitting = mutableSubmitting.asStateFlow()
    @Synchronized fun begin(): Long {
        val attempt = ++generation
        mutableSubmitting.value = true
        return attempt
    }
    @Synchronized fun complete(attempt: Long) {
        if (attempt == generation) mutableSubmitting.value = false
    }
    @Synchronized fun invalidate() {
        generation++
        mutableSubmitting.value = false
    }
    @Synchronized fun isCurrent(attempt: Long): Boolean = attempt == generation
}
