package com.planlock.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Run before, during and after a user UI channel toggle; never changes the system preference itself. */
class NotificationChannelBoundaryInstrumentedTest {
    @Test fun currentUserChannelPreferenceIsRespected() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.qapack3a")
        TokenStore.restore(context.planLockPreferences)
        val manager = context.getSystemService(NotificationManager::class.java)
        val before = requireNotNull(manager.getNotificationChannel("oriva_plan_events"))
        val blocked = InstrumentationRegistry.getArguments().getString("expectedBlocked") == "true"
        assertEquals("Native UI channel state is the prerequisite", blocked, before.importance == NotificationManager.IMPORTANCE_NONE)
        val id = -2_100_000_000_133L
        val scope = TokenStore.state.value.dataScope
        val plan = PlanSnapshotEntity(id, 0, "CHANNEL-QA", "学习", "06:00", "06:02", null, null, null, 1, null, 1, 0).copy(scope = scope)
        val tag = "oriva-plan:$scope"
        try {
            manager.cancel(tag, id.hashCode())
            PlanNotifications.completed(context, plan, 120_000)
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (!blocked && manager.activeNotifications.none { it.tag == tag && it.id == id.hashCode() } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            if (blocked) SystemClock.sleep(600)
            val posted = manager.activeNotifications.any { it.tag == tag && it.id == id.hashCode() }
            val after = requireNotNull(manager.getNotificationChannel("oriva_plan_events")).importance
            context.filesDir.resolve("channel-boundary-proof.json").writeText(JSONObject()
                .put("blocked", blocked).put("posted", posted).put("beforeImportance", before.importance)
                .put("afterImportance", after).put("changedUserPreference", false).toString(), Charsets.UTF_8)
            assertEquals("App must preserve the user's channel preference", before.importance, after)
            assertEquals("Blocked notifications stay absent; re-enabled notifications return", !blocked, posted)
        } finally {
            manager.cancel(tag, id.hashCode())
            val intent = Intent(context, MainActivity::class.java).setData(Uri.Builder().scheme("oriva").authority("plan").appendPath(scope).appendPath(id.toString()).build())
            PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
        }
    }
}
