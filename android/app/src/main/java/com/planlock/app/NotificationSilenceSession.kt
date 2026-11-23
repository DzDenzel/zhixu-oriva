package com.planlock.app

/**
 * Thread-safe tracker for the host hint state requested on a NotificationListenerService instance.
 * Resets on connect, disconnect, and destroy so stale `requested` flags never survive lifecycle changes.
 */
internal class ListenerHintState {
    private var requested: Boolean? = null

    @Synchronized fun currentRequested(): Boolean? = requested

    @Synchronized fun reset() {
        requested = null
    }

    @Synchronized fun applyIfNeeded(required: Boolean, dispatch: (Boolean) -> Boolean): Boolean {
        if (requested == required) return false
        val succeeded = dispatch(required)
        if (succeeded) {
            requested = required
        }
        return succeeded
    }
}

internal interface SilenceListenerHandle {
    fun applyRequired(required: Boolean)
    fun resetRequested()
    fun currentHostHints(): Int?
}

/**
 * Pure state machine backing NotificationSilenceController so lifecycle attachment,
 * disconnection recovery, destroy cleanup, and ListenerRebindGate can be unit-tested on JVM.
 */
internal class NotificationSilenceSession(
    private val isGranted: () -> Boolean,
    private val nowElapsedMs: () -> Long,
    private val requestRebind: () -> Boolean,
    private val onLog: (String) -> Unit = {},
) {
    private var initialized = false
    private var listener: SilenceListenerHandle? = null
    private var required = false
    private val receipts = PlanHintReceipt()
    private val recovery = ListenerRebindGate()

    @Synchronized fun initialize() {
        initialized = true
    }

    @Synchronized fun isInitialized(): Boolean = initialized

    @Synchronized fun isConnected(): Boolean = listener != null

    @Synchronized fun attach(handle: SilenceListenerHandle) {
        handle.resetRequested()
        listener = handle
        receipts.connect()
        handle.applyRequired(required)
        onLog("connected required=$required")
    }

    @Synchronized fun detach(handle: SilenceListenerHandle): Boolean {
        handle.resetRequested()
        if (listener !== handle) return false
        listener = null
        receipts.disconnect()
        onLog("disconnected")
        return true
    }

    @Synchronized fun destroy(handle: SilenceListenerHandle) {
        val wasAttached = listener === handle
        if (wasAttached) {
            handle.applyRequired(false)
            listener = null
            receipts.disconnect()
        }
        handle.resetRequested()
        if (wasAttached && required) {
            refreshConnection()
        }
    }

    @Synchronized fun isRequired(): Boolean = required

    @Synchronized fun observeHints(handle: SilenceListenerHandle, value: Int) {
        if (listener === handle) receipts.observe(value)
    }

    @Synchronized fun prepareReleaseReceipt(): PlanHintReceipt.Ticket? = receipts.beginRelease()

    @Synchronized fun releaseReceiptConfirmed(ticket: PlanHintReceipt.Ticket): Boolean = receipts.confirmed(ticket)

    @Synchronized fun currentHostHints(): Int? = listener?.currentHostHints()

    @Synchronized fun setRequired(value: Boolean) {
        val beginning = value && !required
        required = value
        listener?.applyRequired(value)
        if (beginning || (value && listener == null)) {
            refreshConnection()
        }
    }

    @Synchronized fun recoverIfRequired() {
        if (required) refreshConnection()
    }

    @Synchronized fun refreshConnection(): Boolean {
        if (!initialized) return false
        val granted = isGranted()
        if (!recovery.shouldRequest(granted, listener != null, nowElapsedMs())) return false
        return if (requestRebind()) {
            onLog("rebind-request required=$required")
            true
        } else {
            false
        }
    }
}
