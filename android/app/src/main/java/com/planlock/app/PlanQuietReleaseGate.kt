package com.planlock.app

enum class PlanQuietOutcome { RELEASED, OTHER_ACTIVE_PLAN, UNVERIFIED }

/** A positive public-system probe must precede release success; absent connection is unknown. */
object PlanQuietReleaseGate {
    suspend fun await(
        reconcile: suspend () -> Boolean,
        probe: () -> Boolean?,
        elapsed: () -> Long,
        pause: suspend () -> Unit,
        timeoutMillis: Long = 1_250L,
    ): PlanQuietOutcome {
        if (reconcile()) return PlanQuietOutcome.OTHER_ACTIVE_PLAN
        val deadline = elapsed() + timeoutMillis
        while (true) {
            when (probe()) {
                true -> return PlanQuietOutcome.RELEASED
                null -> return PlanQuietOutcome.UNVERIFIED
                false -> if (elapsed() >= deadline) return PlanQuietOutcome.UNVERIFIED
            }
            pause()
        }
    }
}
