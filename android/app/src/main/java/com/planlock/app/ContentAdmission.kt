package com.planlock.app

import com.planlock.feature.plan.TodayLocalKey
import com.planlock.feature.plan.TodayLocalRead
import com.planlock.feature.plan.TodayLocalState

internal enum class StartupContentStage { BLOCKED, FULL }

internal data class StartupContentDecision(
    val stage: StartupContentStage,
    val read: TodayLocalRead?,
)

internal fun startupContentDecision(
    cold: Boolean,
    splashVisible: Boolean,
    revealComplete: Boolean,
    key: TodayLocalKey,
    localState: TodayLocalState,
): StartupContentDecision {
    val matchingRead = localState.read.takeIf { localState.key == key }
    if (!cold || !splashVisible) {
        return StartupContentDecision(StartupContentStage.FULL, matchingRead)
    }
    if (!revealComplete) {
        return StartupContentDecision(StartupContentStage.BLOCKED, null)
    }
    return if (localState.key == key && localState.phase == com.planlock.feature.plan.TodayLocalPhase.READY) {
        // Mount the final tree during the opaque wordmark hold. Switching trees
        // after the overlay disappears exposes a second entry animation.
        StartupContentDecision(StartupContentStage.FULL, matchingRead)
    } else {
        StartupContentDecision(StartupContentStage.BLOCKED, null)
    }
}
