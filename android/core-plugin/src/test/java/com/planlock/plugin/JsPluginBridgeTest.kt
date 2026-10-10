package com.planlock.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JsPluginBridgeTest {
    private val calls = mutableListOf<String>()
    private val queries = object : PluginQueryProvider {
        override fun queryToday(date: String): String { calls += "today:$date"; return "{\"date\":\"$date\",\"source\":\"host-cache\"}" }
        override fun queryPlan(id: Long): String { calls += "plan:$id"; return "{\"id\":$id}" }
        override fun queryReport(period: String): String { calls += "report:$period"; return "{\"period\":\"$period\"}" }
    }

    @Test fun undeclaredQueriesNeverReachHost() {
        val bridge = JsPluginBridge(emptySet(), EmptyPluginCapabilities, queries)
        val response = Json.parseToJsonElement(bridge.postMessage("{\"op\":\"query.today\",\"args\":{\"date\":\"2026-10-08\"}}"))
        assertEquals("false", response.jsonObject.getValue("ok").jsonPrimitive.content)
        assertTrue(response.toString().contains("CAPABILITY_NOT_DECLARED"))
        assertTrue(calls.isEmpty())
    }

    @Test fun allThreeQueriesReturnHostSnapshotWithoutReaggregation() {
        val bridge = JsPluginBridge(setOf(PluginCapability.QUERY_TODAY, PluginCapability.QUERY_PLAN, PluginCapability.QUERY_REPORT), EmptyPluginCapabilities, queries)
        assertTrue(bridge.postMessage("{\"op\":\"query.today\",\"args\":{\"date\":\"2026-10-08\"}}").contains("host-cache"))
        assertTrue(bridge.postMessage("{\"op\":\"query.plan\",\"args\":{\"id\":42}}").contains("\"id\":42"))
        assertTrue(bridge.postMessage("{\"op\":\"query.report\",\"args\":{\"period\":\"week\"}}").contains("\"period\":\"week\""))
        assertEquals(listOf("today:2026-10-08", "plan:42", "report:week"), calls)
    }

    @Test fun unknownOperationsAndMalformedArgumentsAreRejected() {
        val bridge = JsPluginBridge(PluginCapability.entries.toSet(), EmptyPluginCapabilities, queries)
        assertTrue(bridge.postMessage("{\"op\":\"fetch\",\"args\":{}}").contains("UNKNOWN_OPERATION"))
        assertTrue(bridge.postMessage("not JSON").contains("INVALID_REQUEST"))
        assertTrue(bridge.postMessage("{\"op\":\"query.plan\",\"args\":{\"id\":\"42\"}}").contains("INVALID_REQUEST"))
        assertTrue(bridge.postMessage("{\"op\":\"query.today\",\"args\":{\"date\":\"2026-02-31\"}}").contains("INVALID_REQUEST"))
        assertTrue(bridge.postMessage("{\"op\":\"query.report\",\"args\":{\"period\":\"../../cache\"}}").contains("INVALID_REQUEST"))
        assertTrue(calls.isEmpty())
    }

    @Test fun offlineNegativePlanIdsAreReadAndZeroIsRejected() {
        val bridge = JsPluginBridge(setOf(PluginCapability.QUERY_PLAN), EmptyPluginCapabilities, queries)
        assertTrue(bridge.postMessage("{\"op\":\"query.plan\",\"args\":{\"id\":-42}}").contains("\"id\":-42"))
        assertTrue(bridge.postMessage("{\"op\":\"query.plan\",\"args\":{\"id\":0}}").contains("INVALID_REQUEST"))
        assertEquals(listOf("plan:-42"), calls)
    }

    @Test fun omittedOrEmptyTodayDateUsesHostDefault() {
        val bridge = JsPluginBridge(setOf(PluginCapability.QUERY_TODAY), EmptyPluginCapabilities, queries)
        assertTrue(bridge.postMessage("{\"op\":\"query.today\"}").contains("\"ok\":true"))
        assertTrue(bridge.postMessage("{\"op\":\"query.today\",\"args\":{\"date\":\"\"}}").contains("\"ok\":true"))
        assertEquals(listOf("today:", "today:"), calls)
    }

    @Test fun undeclaredActionsNeverReachHostAndDeclaredActionsUseSameCapability() {
        val capabilities = object : PluginCapabilities {
            override fun overlay(): OverlayCapability? = null
            override fun home() = HomeCapability { calls += "home" }
            override fun haptic() = HapticCapability { calls += "haptic" }
            override fun feedback() = FeedbackCapability { calls += "feedback:$it" }
        }
        val denied = JsPluginBridge(emptySet(), capabilities, queries)
        listOf("home", "haptic", "feedback", "overlay").forEach { op ->
            assertTrue(denied.postMessage("{\"op\":\"$op\"}").contains("CAPABILITY_NOT_DECLARED"))
        }
        assertTrue(calls.isEmpty())
        val allowed = JsPluginBridge(setOf(PluginCapability.HOME, PluginCapability.HAPTIC, PluginCapability.FEEDBACK), capabilities, queries)
        assertTrue(allowed.postMessage("{\"op\":\"home\"}").contains("\"ok\":true"))
        allowed.postMessage("{\"op\":\"haptic\"}")
        allowed.postMessage("{\"op\":\"feedback\",\"args\":{\"code\":\"focus_tip\"}}")
        assertEquals(listOf("home", "haptic", "feedback:focus_tip"), calls)
    }

    @Test fun repeatedCallsCannotFloodTheHost() {
        val bridge = JsPluginBridge(setOf(PluginCapability.QUERY_PLAN), EmptyPluginCapabilities, queries)
        repeat(256) { assertTrue(bridge.postMessage("{\"op\":\"query.plan\",\"args\":{\"id\":42}}").contains("\"ok\":true")) }
        assertTrue(bridge.postMessage("{\"op\":\"query.plan\",\"args\":{\"id\":42}}").contains("CALL_LIMIT"))
        assertEquals(256, calls.size)
    }

    @Test fun hostFailuresBecomeBoundedErrorResponses() {
        val broken = object : PluginQueryProvider {
            override fun queryToday(date: String): String = error("internal host state")
            override fun queryPlan(id: Long): String = "not json"
            override fun queryReport(period: String): String = "{}"
        }
        val bridge = JsPluginBridge(setOf(PluginCapability.QUERY_TODAY, PluginCapability.QUERY_PLAN), EmptyPluginCapabilities, broken)
        assertTrue(bridge.postMessage("{\"op\":\"query.today\",\"args\":{\"date\":\"2026-10-08\"}}").contains("HOST_ERROR"))
        assertFalse(bridge.postMessage("{\"op\":\"query.today\",\"args\":{\"date\":\"2026-10-08\"}}").contains("internal host state"))
        assertTrue(bridge.postMessage("{\"op\":\"query.plan\",\"args\":{\"id\":1}}").contains("HOST_ERROR"))
    }
}
