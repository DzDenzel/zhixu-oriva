package com.planlock.app

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.*
import com.planlock.app.api.dto.*
import com.planlock.app.record.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.AppSync
import com.planlock.app.sync.SyncPhase
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.UUID

/** Explicitly invoked QA scenarios. Only the disposable QA app and dedicated backend are used. */
class DevicePackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also { check(it.packageName == "com.planlock.qapack3a") }
    private fun activity() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    private fun walk(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { walk(node.getChild(it)) }
    private fun nodes() = walk(instrumentation.uiAutomation.rootInActiveWindow)
    private fun waitText(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < end) {
            nodes().lastOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
            SystemClock.sleep(30)
        }
        error("Missing $text: ${nodes().mapNotNull { it.text }}")
    }
    private fun click(node: AccessibilityNodeInfo) {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty)
        val start = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
    }
    private fun proof(name: String, body: String) = context.filesDir.resolve(name).writeText(body, Charsets.UTF_8)

    @Test fun capabilitiesReflectTheGrantedSystemSettings() {
        val access = com.planlock.app.security.CapabilityAccess(context)
        val facts = com.planlock.app.security.Capability.entries.associateWith(access::check)
        for (capability in com.planlock.app.security.Capability.entries) {
            assertEquals(capability.name, capability != com.planlock.app.security.Capability.LOCATION, facts[capability])
            assertNotNull(access.request(capability).settingsIntent.resolveActivity(context.packageManager))
        }
        assertEquals("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS",
            access.request(com.planlock.app.security.Capability.DND).settingsIntent.action)
        proof("device-capabilities.json",facts.entries.joinToString(prefix="{",postfix="}") { "\"${it.key}\":${it.value}" })
    }

    @Test fun prepareLocalOnlyDiaryAndCheckSummary() = runBlocking {
        ExecutionHistoryUiFixture().prepareOfflineUiScenario()
        val store = RecordStore(context)
        // Leave a useful read-only diary; no active QA plan should change the owner's DND state.
        store.plan(-5702)?.let { store.updateLocalPlan(it.copy(status=0)) }
        assertNull(ConnectionStore.state.value.config)
        val summary = RhythmRepository(store).snapshot(store.currentScope(),OccurrenceDate.from(Calendar.getInstance())).summary!!
        // The existing walkthrough fixture straddles minute-rounded plan bounds;
        // numeric clipping is covered by the fixed golden fixture, this proves D1's nonempty local diary.
        assertTrue(summary.focusMinutes30d > 0)
        assertEquals(RhythmSource.LOCAL,summary.source)
        proof("device-local-statistics.json","""{"localOnly":true,"scope":"local/local","focusMinutes30d":${summary.focusMinutes30d},"completed":${summary.days.sumOf { it.completed }}}""")
    }

    @Test fun exportsAndServerRhythmRemainConsistent() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        val api = ApiClient.create(false)
        val csv = api.exportRecords("csv").use { it.string() }
        val json = api.exportRecords("json").use { it.string() }
        val ics = api.exportPlans().use { it.string() }
        assertTrue(csv.startsWith("type,plan_id,") && csv.contains("\nexecution,"))
        assertTrue(kotlinx.serialization.json.Json.parseToJsonElement(json).toString().contains("executions"))
        assertTrue(ics.contains("BEGIN:VCALENDAR") && ics.contains("BEGIN:VEVENT"))
        val local = RhythmRepository(store).snapshot(store.currentScope(),OccurrenceDate.from(Calendar.getInstance())).summary!!
        assertEquals(local.focusMinutes30d,requireNotNull(api.rhythm().data).focusMinutes30d)
        proof("device-export-proof.json","""{"csv":true,"json":true,"ics":true,"focusMinutes30d":${local.focusMinutes30d},"localServerMatch":true}""")
    }

    @Test fun prepareConnectedDiaryAndTwelveDeferredChanges() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val sync = AppSync.get(context)
        val url = InstrumentationRegistry.getArguments().getString("qaBaseUrl") ?: error("qaBaseUrl is required")
        check(url in setOf("http://127.0.0.1:18082/api/v1/", "http://10.0.2.2:18082/api/v1/"))
        sync.withSessionChange {
            TokenStore.changeConnection(context.planLockPreferences, ConnectionConfig(instanceLabel="Device QA",
                apiBaseUrl=url, pluginsCatalogUrl=url+"plugins/catalog/", lanAllowed=true))
            val auth = requireNotNull(ApiClient.create(false).deviceRegister(DeviceRegisterRequest("device-pack-${UUID.randomUUID()}", "QA", "android")).data)
            TokenStore.save(context.planLockPreferences, AuthSession(auth.token,auth.userId,auth.deviceId),ConnectionStore.state.value)
        }
        val store = RecordStore(context)
        repeat(12) { index -> store.updateLocalPlan(PlanSnapshotEntity(-211000L-index,TokenStore.userId!!,"QA-DEVICE-$index","学习",
            "07:00:00","08:00:00","{\"type\":\"daily\"}",null,null,0,null,1,0)) }
        sync.pushPendingRecords()
        val plan = store.plans().first()
        val today = OccurrenceDate.from(Calendar.getInstance()).let { "%04d-%02d-%02d".format(it.year,it.month,it.day) }
        store.enqueueExecution(ExecutionRecordRequest(plan.id,today,1,startedAt="${today}T07:00:00",endedAt="${today}T08:00:00",clientId=UUID.randomUUID().toString()))
        sync.pushPendingRecords()
        sync.coordinator.refresh(false).join()
        assertNull(sync.coordinator.state.value.error)
        assertTrue(store.executionHistory().any { it.status==1 })
        store.plans().forEach { store.updateLocalPlan(it.copy(name=it.name+"-edited")) }
        assertEquals(12,store.pending().size)
        proof("device-setup.json","""{"pending":12,"records":${store.executionHistory().size},"scope":"${context.packageName}"}""")
    }

    @Test fun backgroundNetworkRoundStopsAtTwentySecondsAndKeepsData() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val sync = AppSync.get(context)
        val store = RecordStore(context)
        assertTrue(store.pending().size >= 12)
        activity()
        val start = SystemClock.elapsedRealtime()
        val job = sync.coordinator.refresh(false)
        delay(150)
        assertFalse(sync.coordinator.state.value.running)
        assertEquals(SyncPhase.BACKGROUND,sync.coordinator.state.value.phase)
        withTimeout(23_000) { job.join() }
        val elapsed = SystemClock.elapsedRealtime()-start
        assertTrue("Round exceeded limit: $elapsed",elapsed <= 21_000)
        assertEquals(SyncPhase.FAILED,sync.coordinator.state.value.phase)
        assertFalse(sync.coordinator.state.value.backgroundRunning)
        assertTrue(store.pending().size >= 12)
        proof("device-timeout.json","""{"elapsedMillis":$elapsed,"spinnerDuringBackground":false,"phase":"FAILED","pending":${store.pending().size}}""")
    }

    @Test fun retryUploadsAndStatisticsPaintFromRoomAndExportsRemainAvailable() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val sync = AppSync.get(context)
        val store = RecordStore(context)
        activity()
        withTimeout(23_000) { sync.coordinator.refresh().join() }
        assertNull(sync.coordinator.state.value.error)
        assertTrue(store.pending().isEmpty())
        val summary = RhythmRepository(store).snapshot(store.currentScope(),OccurrenceDate.from(Calendar.getInstance())).summary!!
        assertTrue(summary.focusMinutes30d >= 60)
        val button = waitText("统计")
        val start = SystemClock.elapsedRealtime()
        click(button)
        while(nodes().none { it.text?.contains("近30天专注") == true } && SystemClock.elapsedRealtime()-start < 1_200) SystemClock.sleep(10)
        val paint = SystemClock.elapsedRealtime()-start
        assertTrue("Statistics first paint: $paint",nodes().any { it.text?.contains("近30天专注") == true })
        assertTrue("Statistics first paint: $paint",paint <= 1_000)
        val api = ApiClient.create(false)
        val csv = api.exportRecords("csv").use { it.string() }
        val json = api.exportRecords("json").use { it.string() }
        val ics = api.exportPlans().use { it.string() }
        assertTrue(csv.startsWith("type,plan_id,") && csv.contains("\nexecution,"))
        assertTrue(kotlinx.serialization.json.Json.parseToJsonElement(json).toString().contains("executions"))
        assertTrue(ics.contains("BEGIN:VCALENDAR") && ics.contains("BEGIN:VEVENT"))
        assertEquals(summary.focusMinutes30d,requireNotNull(api.rhythm().data).focusMinutes30d)
        proof("device-retry-stats.json","""{"pending":0,"focusMinutes30d":${summary.focusMinutes30d},"firstPaintMillis":$paint,"csv":true,"json":true,"ics":true}""")
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.filesDir.resolve("device-statistics.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        Unit
    }

    @Test fun statisticsPaintDoesNotWaitForTheUnavailableBackend() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        val summary = RhythmRepository(store).snapshot(store.currentScope(),OccurrenceDate.from(Calendar.getInstance())).summary!!
        assertTrue(summary.focusMinutes30d > 0)
        activity()
        val button = waitText("统计")
        val start = SystemClock.elapsedRealtime()
        click(button)
        while(nodes().none { it.text?.contains("近30天专注") == true } && SystemClock.elapsedRealtime()-start < 1_200) SystemClock.sleep(10)
        val paint = SystemClock.elapsedRealtime()-start
        assertTrue(nodes().any { it.text?.contains("近30天专注") == true })
        assertTrue("Offline statistics first paint: $paint",paint<=1_000)
        proof("device-offline-statistics.json","""{"firstPaintMillis":$paint,"focusMinutes30d":${summary.focusMinutes30d},"connected":${ConnectionStore.state.value.config != null},"renderedWithoutWaitingForBackend":true}""")
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.filesDir.resolve("device-offline-statistics.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        Unit
    }
}
