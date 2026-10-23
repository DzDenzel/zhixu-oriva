package com.planlock.app

import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.TokenStore
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.AppSync
import com.planlock.app.sync.SyncOverview
import com.planlock.plugin.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Only run against the isolated QA application; owner data is never a test fixture. */
class CachedPluginQueriesInstrumentedTest {
    @Test fun allNativeQueriesUseRealHostRoomAndReportCacheWithoutSyncAttempt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.qajs") { "Requires isolated QA application" }
        val store = RecordStore(context)
        val sync = AppSync.get(context)
        val plan = PlanSnapshotEntity(-9001, 7000, "离线查询夹具", "学习", "21:00:00", "23:00:00", "{\"type\":\"daily\"}", null, null, 15, null, 1, 0)
        val cached = sync.cache.read(TokenStore.userId)
        val report = StatsResponse(period = "week", continuousDays = 6, completedCount = 4)
        store.saveLocalPlan(plan)
        sync.cache.save(TokenStore.userId, cached.copy(statsByPeriod = cached.statsByPeriod + ("week" to report)))
        try {
            val before = sync.coordinator.state.value.attempts
            val projection = loadAssistantSnapshot(store, sync)
            val queries = CachedPluginQueries(context)
            val manifest = PluginManifest("query-test", "Query test", "1", 2, "main", "official", PluginSourceType.OFFICIAL,
                setOf(PluginCapability.QUERY_TODAY, PluginCapability.QUERY_PLAN, PluginCapability.QUERY_REPORT), type = PluginType.JS)
            val source = """function main(e,host){let call=(op,args)=>JSON.parse(host.postMessage(JSON.stringify({op,args}))).data;return {today:call('query.today',{}),plan:call('query.plan',{id:-9001}),report:call('query.report',{period:'week'})};}"""
            val runtime = JsPluginRuntime(manifest, source, queries)
            val result = try { Json.parseToJsonElement(runtime.initialize()).jsonObject } finally { runtime.close() }
            assertEquals(JsonArray(projection.arrangements.map(::JsonPrimitive)), result.getValue("today").jsonObject["arrangements"])
            assertEquals("离线查询夹具", result.getValue("plan").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement(Json.encodeToString(report)), result.getValue("report"))
            assertEquals("Read-only queries must not initiate refresh", before, sync.coordinator.state.value.attempts)
        } finally {
            com.planlock.app.record.PlanLockDatabase.get(context).planSnapshotDao().delete("local\nlocal", plan.id)
            sync.cache.save(TokenStore.userId, cached)
        }
    }
}
