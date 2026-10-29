package com.planlock.app

/** Lifecycle and callbacks are serialized by NotificationSilenceController's monitor. */
class PlanHintReceipt {
    data class Ticket(val connection: Long, val serial: Long, val afterCallback: Long)
    private var connection = 0L
    private var serial = 0L
    private var callbacks = 0L
    private var connected = false
    private var hints: Int? = null
    private var pending: Ticket? = null
    private var acknowledged: Ticket? = null
    fun connect() { connection++; connected = true; hints = null; pending = null; acknowledged = null }
    fun disconnect() { connected = false; hints = null; pending = null; acknowledged = null }
    fun observe(value: Int) {
        if (!connected) return
        callbacks++; hints = value
        val ticket = pending
        if (value and 2 == 0 && ticket != null && callbacks > ticket.afterCallback) acknowledged = ticket
    }
    fun beginRelease(): Ticket? {
        // A real activation callback is required; a getter's fallback zero is never an acknowledgement.
        if (!connected || hints == null || requireNotNull(hints) and 2 == 0) return null
        return Ticket(connection, ++serial, callbacks).also { pending = it; acknowledged = null }
    }
    fun confirmed(ticket: Ticket): Boolean = connected && ticket.connection == connection &&
        acknowledged == ticket && hints != null && requireNotNull(hints) and 2 == 0
}
