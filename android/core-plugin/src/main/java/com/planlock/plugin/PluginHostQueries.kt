package com.planlock.plugin

/** Registered by the application. Delegation keeps loaded plugins on the current user cache. */
object PluginHostQueries : PluginQueryProvider {
    @Volatile var provider: PluginQueryProvider = EmptyPluginQueryProvider
    override fun queryToday(date: String): String = provider.queryToday(date)
    override fun queryPlan(id: Long): String = provider.queryPlan(id)
    override fun queryReport(period: String): String = provider.queryReport(period)
}

object PluginHostConfiguration {
    @Volatile var read: (PluginManifest, Long) -> Map<String, String> = { _, _ -> emptyMap() }
}
