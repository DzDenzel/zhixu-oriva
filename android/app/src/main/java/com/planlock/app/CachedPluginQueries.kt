package com.planlock.app

import android.content.Context
import com.planlock.app.api.TokenStore
import com.planlock.app.record.RecordStore
import com.planlock.app.sync.AppSync
import com.planlock.plugin.PluginQueryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Reads Room and the same per-user cache as visible pages. No API client exists here. */
class CachedPluginQueries(context: Context) : PluginQueryProvider {
    private val store = RecordStore(context.applicationContext)
    private val sync = AppSync.get(context.applicationContext)
    override fun queryToday(date: String): String = runBlocking(Dispatchers.IO) {
        val value = loadAssistantSnapshot(store, sync, date)
        buildJsonObject {
            put("today", value.today)
            put("arrangements", JsonArray(value.arrangements.map(::JsonPrimitive)))
            put("plans", JsonArray(value.plans.map { plan -> buildJsonObject {
                put("id", plan.id); put("name", plan.name); put("enabled", plan.enabled)
                put("inWindow", plan.inWindow); put("remainingMinutes", plan.remainingMinutes)
            } }))
            put("continuousDays", value.continuousDays?.let(::JsonPrimitive) ?: JsonNull)
            put("todayUnlocks", value.todayUnlocks?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }
    override fun queryPlan(id: Long): String = runBlocking(Dispatchers.IO) {
        val userId = TokenStore.userId
        val plan = store.plan(id)
        check(userId == TokenStore.userId) { "User changed during query" }
        plan?.let { buildJsonObject {
            put("id", it.id); put("name", it.name); put("type", it.type)
            put("startTime", it.startTime?.let(::JsonPrimitive) ?: JsonNull)
            put("endTime", it.endTime?.let(::JsonPrimitive) ?: JsonNull)
            put("repeatRule", it.repeatRule?.let(::JsonPrimitive) ?: JsonNull)
            put("status", it.status?.let(::JsonPrimitive) ?: JsonNull)
            put("version", it.version?.let(::JsonPrimitive) ?: JsonNull)
        }.toString() } ?: "null"
    }
    override fun queryReport(period: String): String = runBlocking(Dispatchers.IO) {
        require(period in setOf("day", "week", "month"))
        val userId = TokenStore.userId
        val report = sync.cache.read(userId).stats(period)
        check(userId == TokenStore.userId) { "User changed during query" }
        report?.let { Json.encodeToString(it) } ?: "null"
    }
}
