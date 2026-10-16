package com.planlock.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.*
import com.planlock.app.record.RecordStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** UI + real Android DataStore + real HTTP; refuses to touch the normal installation. */
class ConnectionFlowInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also { check(it.packageName == "com.planlock.qareq032") }
    private val automation get() = instrumentation.uiAutomation

    private fun root(): AccessibilityNodeInfo? = automation.rootInActiveWindow
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    }
    private fun find(text: String): AccessibilityNodeInfo? = nodes(root()).firstOrNull {
        it.text?.contains(text) == true || it.contentDescription?.contains(text) == true
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) { find(text)?.let { return it }; SystemClock.sleep(150) }
        screenshot("failure")
        error("UI text not found: $text; visible=" + nodes(root()).mapNotNull { it.text ?: it.contentDescription }.joinToString("|").take(500))
    }
    private fun click(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var node: AccessibilityNodeInfo? = nodes(root()).firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
                ?: find(text)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(150) // Compose may still be publishing enabled semantics after ACTION_SET_TEXT.
        }
        screenshot("failure-click")
        error("UI not clickable: $text")
    }
    private fun edit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable || node.className?.toString() == "android.widget.EditText") return node
        for (i in 0 until node.childCount) edit(node.getChild(i))?.let { return it }
        return null
    }
    private fun screenshot(name: String) {
        val bitmap = automation.takeScreenshot() ?: error("No screenshot")
        try { File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun localFirstTextImportRealHttpAndRetainedWorkspace() = runBlocking {
        val preferences = context.planLockPreferences
        TokenStore.restore(preferences)
        TokenStore.changeConnection(preferences, null)
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("设置")
        assertNull(ConnectionStore.state.value.config)
        screenshot("01-local-home")
        click("设置"); click("后端连接")
        waitFor("仅本地使用")
        waitFor("粘贴连接配置")
        val config = ConnectionConfig(1, "内网验证实例", "http://127.0.0.1:18732/api/v1/", "http://127.0.0.1:18732/plugins/catalog/", true)
        val field = checkNotNull(edit(root()))
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, config.encode()) }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        click("校验并预览"); waitFor("确认连接后端")
        assertNull("Preview must not change connection", ConnectionStore.state.value.config)
        screenshot("02-preview")
        click("确认连接"); waitFor("连接已保存")
        assertEquals(config, ConnectionStore.state.value.config)
        screenshot("03-connected")
        ConnectionStore.restore(preferences)
        assertEquals(config, ConnectionStore.state.value.config)
        val api = ApiClient.create(false)
        val registration = api.deviceRegister(com.planlock.app.api.dto.DeviceRegisterRequest("req032-test", "QA", "android"))
        assertEquals(0, registration.code)
        val auth = checkNotNull(registration.data)
        TokenStore.save(preferences, AuthSession(auth.token, auth.userId, auth.deviceId))
        assertEquals(0, api.plans().code)
        val before = RecordStore(context).pending().size
        com.planlock.app.sync.AppSync.get(context).withSessionChange { TokenStore.changeConnection(preferences, config.copy(apiBaseUrl = "https://other.invalid/api/v1/")) }
        assertTrue(TokenStore.state.value.ownershipMismatch)
        assertNull(TokenStore.token)
        assertEquals(before, RecordStore(context).pending().size)
        screenshot("04-owner-protected")
        com.planlock.app.sync.AppSync.get(context).withSessionChange { TokenStore.changeConnection(preferences, null) }
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        click("计划"); waitFor("设置")
        screenshot("05-offline-plans")
        assertNull(ConnectionStore.state.value.config)
        assertNull(com.planlock.app.sync.AppSync.get(context).coordinator.state.value.error)
        TokenStore.changeConnection(preferences, config) // Leave the imported document for a separate cold-process check.
    }
}
