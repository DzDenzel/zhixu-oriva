package com.planlock.app

/** Suppresses duplicate lifecycle requests; no timer, polling or automatic grant. */
internal class ListenerRebindGate(private val minimumIntervalMillis: Long = 10_000) {
    private var lastRequestAt: Long? = null
    fun shouldRequest(granted: Boolean, connected: Boolean, nowMillis: Long): Boolean {
        if (!granted || connected) return false
        if (lastRequestAt?.let { nowMillis - it < minimumIntervalMillis } == true) return false
        lastRequestAt = nowMillis
        return true
    }
}
