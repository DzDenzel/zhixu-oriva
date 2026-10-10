package com.planlock.plugin

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PluginLoadStateInstrumentedTest {
    @Test fun disablingWhileLoadIsPendingCannotPublishTheOldFailure() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.core.plugin.test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val previousQueries = PluginHostQueries.provider
        PluginHostQueries.provider = object : PluginQueryProvider {
            override fun queryPlan(id: Long): String { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); return "{}" }
            override fun queryToday(date: String) = "{}"
            override fun queryReport(period: String) = "{}"
        }
        val file = java.io.File(context.filesDir, "late-load-fixture.pljs")
        instrumentation.context.assets.open("late-load-fixture.pljs").use { input -> file.outputStream().use { input.copyTo(it) } }
        val store = PluginStateStore(context)
        val initial = store.snapshot()
        val id = "late-load-fixture"
        val manifest = PluginManifest(id, "Late load", "1", 2, "main", "official", PluginSourceType.OFFICIAL,
            requiredCapabilities = setOf(PluginCapability.QUERY_PLAN), type = PluginType.JS)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val platform = PluginPlatform.get(context)
        try {
            store.save(InstalledPluginRecord(manifest, file.path, hash, "", enabled = true))
            platform.setGlobalEnabled(true)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val before = platform.completedLoads.value
            platform.setPluginEnabled(id, false)
            release.countDown()
            withTimeout(5000) { platform.completedLoads.first { it > before } }
            val record = platform.snapshot().installed.first { it.manifest.id == id }
            assertFalse(record.enabled)
            assertNull("A superseded load must not overwrite the disabled record", record.lastError)
        } finally {
            release.countDown(); platform.uninstall(id); platform.setGlobalEnabled(initial.globalEnabled)
            PluginHostQueries.provider = previousQueries
        }
    }
    @Test fun failedBackgroundLoadPublishesItsPersistentFactWithoutPageResume() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.core.plugin.test")
        val store = PluginStateStore(context)
        val initial = store.snapshot()
        val id = "missing-load-fixture"
        val manifest = PluginManifest(id, "Missing", "1", 2, "main", "official", PluginSourceType.OFFICIAL, type = PluginType.JS)
        try {
            store.save(InstalledPluginRecord(manifest, java.io.File(context.filesDir, "does-not-exist.pljs").path, "", "", enabled = true))
            val platform = PluginPlatform.get(context)
            platform.setGlobalEnabled(true)
            val failed = withTimeout(5000) { platform.snapshots.first { it.installed.any { row -> row.manifest.id == id && row.lastError != null } } }
            assertTrue(failed.installed.first { it.manifest.id == id }.lastError!!.contains("missing", ignoreCase = true))
        } finally {
            store.uninstall(id)
            PluginPlatform.get(context).setGlobalEnabled(initial.globalEnabled)
        }
    }

    @Test fun independentDecisionAndActionDoNotTimeoutWhileWaitingForAnUnneededMonitor() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val queries = object : PluginQueryProvider {
            override fun queryPlan(id: Long): String { started.countDown(); return "{}" }
            override fun queryToday(date: String) = "{}"
            override fun queryReport(period: String) = "{}"
        }
        val manifest = PluginManifest("concurrent", "Concurrent", "1", 2, "main", "official", PluginSourceType.OFFICIAL,
            requiredCapabilities = setOf(PluginCapability.QUERY_PLAN), type = PluginType.JS)
        val source = """function main(e,h){if(e.kind==='decision'){h.postMessage(JSON.stringify({op:'query.plan',args:{id:1}}));let end=Date.now()+1500;while(Date.now()<end){};return null;}let end=Date.now()+1000;while(Date.now()<end){};return {message:'done'};}"""
        val runtime = JsPluginRuntime(manifest, source, queries)
        val decision = java.util.concurrent.Executors.newSingleThreadExecutor()
        val actions = PluginActionExecutor()
        try {
            val future = decision.submit { runtime.onDecision(PluginDecision(1, "example.app", null, true), EmptyPluginCapabilities) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val result = actions.execute {
                try { PluginActionResult(PluginActionStatus.EXECUTED, runtime.onAction(PluginActionEvent(1, "run"))) }
                catch (error: Throwable) { failure.set(error); throw error }
            }
            assertEquals("$result; native failure=${failure.get()}", PluginActionStatus.EXECUTED, result.status)
            assertEquals("done", result.message)
            assertFalse(actions.isQuarantined)
            future.get(3, TimeUnit.SECONDS)
        } finally { actions.close(); runtime.close(); decision.shutdownNow() }
    }
}
