package com.planlock.app

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.InsightFeedback
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.LocalInsightRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Isolated emulator installation only; never changes the normal app or a physical device. */
class InsightRepositoryInstrumentedTest {
    @Test fun localContractAndStatsEmptySlotOnEmulator() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.qainsight")
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        TokenStore.restore(context.planLockPreferences)
        TokenStore.changeConnection(context.planLockPreferences, null)
        val repository = LocalInsightRepository(TokenStore.state.value.dataScope)
        assertTrue(repository.observe().first().isEmpty())
        repository.setFeedback("reserved-id", InsightFeedback.LIKE_ME)
        assertTrue(repository.observe().first().isEmpty())

        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val automation = instrumentation.uiAutomation
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
            else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        fun waitFor(text: String): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
                SystemClock.sleep(100)
            }
            error("UI text not found: $text")
        }
        var tab: AccessibilityNodeInfo? = waitFor("统计")
        while (tab != null && !tab.isClickable) tab = tab.parent
        val enteredAt = SystemClock.elapsedRealtime()
        assertTrue(tab?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        waitFor("完成率")
        instrumentation.waitForIdleSync()
        val allNodes = nodes(automation.rootInActiveWindow)
        val visible = allNodes.mapNotNull { it.text?.toString() }
        assertFalse(visible.any { it.contains("知行对你的了解") })
        val output = File(context.getExternalFilesDir(null), "insight-proof.txt")
        output.writeText("scope=${repository.dataScope}\nempty=true\nstatsNavigationMs=${SystemClock.elapsedRealtime() - enteredAt}\n" + visible.joinToString("\n"), Charsets.UTF_8)
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            File(context.getExternalFilesDir(null), "stats-empty.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
