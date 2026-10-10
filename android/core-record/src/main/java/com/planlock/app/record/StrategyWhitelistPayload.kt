package com.planlock.app.record

import com.planlock.app.api.dto.AppItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Compatibility adapter between old CSV snapshots and the AppItem JSON wire contract. */
object StrategyWhitelistPayload {
    private val serializer = ListSerializer(AppItem.serializer())
    fun normalizeLegacy(raw: String?): String? {
        if (raw == null) return null
        if (raw.trimStart().startsWith("[")) {
            Json.decodeFromString(serializer, raw) // Invalid JSON stays retryable instead of losing entries.
            return raw
        }
        val items = csv(raw).map { token -> AppItem(token, token.takeIf { '.' in it }.orEmpty()) }
        return Json.encodeToString(serializer, items)
    }
    fun tokens(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        if (!raw.trimStart().startsWith("[")) return csv(raw)
        return Json.decodeFromString(serializer, raw).mapNotNull { item ->
            item.packageName?.takeIf { it.isNotBlank() } ?: item.name?.takeIf { it.isNotBlank() }
        }
    }
    private fun csv(raw: String) = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
