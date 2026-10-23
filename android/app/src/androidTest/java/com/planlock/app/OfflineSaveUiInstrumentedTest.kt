package com.planlock.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.RecordStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Platform accessibility tree timing avoids host-side dump latency in the one-second assertion. */
class OfflineSaveUiInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private fun walk(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { walk(node.getChild(it)) }
    private fun nodes() = walk(ui.rootInActiveWindow)
    private fun matches(node: AccessibilityNodeInfo, text: String) = node.text?.toString()?.trim() == text || node.contentDescription?.toString()?.trim() == text
    private fun find(text: String) = nodes().lastOrNull { matches(it,text) }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis()+12_000
        while(SystemClock.uptimeMillis()<end) {
            find(text)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Missing $text: " + nodes().mapNotNull { it.text?.toString() })
    }
    private fun click(text: String) {
        val label = waitFor(text)
        val target = if (text == "全天") walk(label.parent).firstOrNull { it.isCheckable && it.isClickable } ?: label else label
        tap(target)
        SystemClock.sleep(300)
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty) { "No visible touch target" }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(ui.injectInputEvent(event,true));event.recycle()
        }
    }

    @Test fun saveClosesEditorWithinOneSecondWithTwelveSlowFailingIntents() = runBlocking {
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.qapack3a")
        TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        val before = store.pending().size
        assertTrue(before >= 12)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("设置");click("计划");waitFor("新建计划")
        click("日程");waitFor("新建日程");click("新建日程");waitFor("保存日程")
        val field = nodes().firstOrNull { it.isEditable } ?: error("Missing title field")
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"QA-SAVE-NETWORK-108") }))
        click("全天")
        val button = waitFor("保存日程")
        val start = SystemClock.uptimeMillis()
        tap(button)
        while(find("保存日程") != null && SystemClock.uptimeMillis()-start <= 1_500) SystemClock.sleep(10)
        val elapsed = SystemClock.uptimeMillis()-start
        assertTrue("Editor still visible after ${elapsed}ms",find("保存日程") == null)
        assertTrue("Save took ${elapsed}ms",elapsed <= 1_000)
        assertTrue(store.schedules().any { it.title == "QA-SAVE-NETWORK-108" })
        assertTrue(store.pending().size >= before+1)
        waitFor("同步未完成 · 详情")
        context.filesDir.resolve("slow-save-ui.json").writeText("""{"editorClosedMillis":$elapsed,"pendingBefore":$before,"pendingAfter":${store.pending().size},"unsyncedVisible":true}""",Charsets.UTF_8)
        ui.takeScreenshot()?.let { bitmap -> context.filesDir.resolve("slow-save-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        Unit
    }
}
