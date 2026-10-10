package com.planlock.plugin

/** Optional response timing, not another decision function or executable contribution UI. */
interface TimedPluginResponse { fun responseDelayMillis(decision: PluginDecision): Long? }

/** Only the host observes/rechecks the foreground and current plan before delivering a response. */
fun interface PluginResponseScheduler {
    fun schedule(expected: PluginDecision, delayMillis: Long, onReady: (PluginDecision) -> Unit): AutoCloseable
}

internal class TimedResponseRegistry {
    private val pending = mutableMapOf<String, AutoCloseable>()
    private val tokens = mutableMapOf<String, Any>()
    @Synchronized fun cancel(id: String) { tokens.remove(id); pending.remove(id)?.close() }
    @Synchronized fun retain(ids: Set<String>) { tokens.keys.toList().filterNot { it in ids }.forEach(::cancel) }
    @Synchronized fun schedule(id: String, delay: Long, decision: PluginDecision, scheduler: PluginResponseScheduler,
        callback: (PluginDecision) -> Unit) {
        cancel(id)
        val token = Any()
        tokens[id] = token
        pending[id] = scheduler.schedule(decision, delay.coerceIn(1, 900_000)) { fresh ->
            val current = synchronized(this) {
                if (tokens[id] !== token) false else { tokens.remove(id); pending.remove(id); true }
            }
            if (current) callback(fresh)
        }
    }
}
