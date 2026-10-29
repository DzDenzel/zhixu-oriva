package com.planlock.app.sync

/** The AI page consumes observed sync outcomes, never a new page-entry network probe. */
enum class BackendConnectionState { CONNECTED, UNREACHABLE, LOCAL_ONLY }

fun backendConnectionState(configured: Boolean, sync: SyncState): BackendConnectionState = when {
    !configured -> BackendConnectionState.LOCAL_ONLY
    sync.lastRoundSucceeded -> BackendConnectionState.CONNECTED
    else -> BackendConnectionState.UNREACHABLE
}
