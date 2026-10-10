package com.planlock.app.sync

/** Process-owned lifecycle state survives Activity replacement without retaining it. */
class ForegroundSyncGate {
    private var pendingReturn = false

    @Synchronized fun stopped(changingConfigurations: Boolean) {
        if (!changingConfigurations) pendingReturn = true
    }

    /** Attachment and configuration recreation are not foreground transitions. */
    @Synchronized fun resumed(): Boolean {
        val shouldRefresh = pendingReturn
        pendingReturn = false
        return shouldRefresh
    }
}
