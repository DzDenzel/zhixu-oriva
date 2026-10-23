package com.planlock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Explicit diagnostics only; every temporary registration is removed in finally. */
class ExactAlarmProbeInstrumentedTest {
    @Test fun registerPublicExactApisForSystemInspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.qapack3a")
        val manager = context.getSystemService(AlarmManager::class.java)
        try {
        val rows = (0..3).map { index ->
            val pending = PendingIntent.getBroadcast(context, 219100 + index,
                Intent(context, PlanAlarmReceiver::class.java).setAction("qa.exact.probe.$index")
                    .setData(Uri.parse("oriva://exact-probe/$index")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val error = runCatching {
                val at = System.currentTimeMillis() + 30 * 60_000 + index * 60_000
                when (index) {
                    0 -> manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                    1 -> manager.setExact(AlarmManager.RTC_WAKEUP, at, pending)
                    2 -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                    else -> manager.setAlarmClock(AlarmManager.AlarmClockInfo(at,
                        PendingIntent.getActivity(context, 219103, Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)), pending)
                }
            }.exceptionOrNull()
            """{"api":$index,"error":${error?.let { "\"${it.javaClass.simpleName}\"" } ?: "null"}}"""
        }
        context.filesDir.resolve("exact-api-proof.json").writeText(
            """{"allowed":${manager.canScheduleExactAlarms()},"calls":[${rows.joinToString(",")}]}""", Charsets.UTF_8)
        val dump = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("dumpsys alarm")).bufferedReader().use { it.readText() }
        val lines = dump.lines()
        val indices = lines.indices.filter { "action qa.exact.probe." in lines[it] }
        context.filesDir.resolve("exact-api-os-proof.txt").writeText(indices.joinToString("\n\n") {
            lines.subList(it, minOf(it + 7, lines.size)).joinToString("\n")
        }, Charsets.UTF_8)
        } finally { cleanupPublicExactApiProbes() }
    }

    fun cleanupPublicExactApiProbes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.qapack3a")
        val manager = context.getSystemService(AlarmManager::class.java)
        for (index in 0..3) {
            PendingIntent.getBroadcast(context, 219100 + index,
                Intent(context, PlanAlarmReceiver::class.java).setAction("qa.exact.probe.$index")
                    .setData(Uri.parse("oriva://exact-probe/$index")), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                manager.cancel(it); it.cancel()
            }
        }
        PendingIntent.getActivity(context, 219103, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
    }

    @Test fun publicAlarmClockActuallyDeliversWithoutOpeningAnActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.qapack3a")
        val manager = context.getSystemService(AlarmManager::class.java)
        val due = System.currentTimeMillis() + 30_000
        val latch = CountDownLatch(1)
        var delivered = 0L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                delivered = System.currentTimeMillis()
                latch.countDown()
            }
        }
        val action = "com.planlock.qa.CLOCK_DELIVERY_PROBE"
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val pending = PendingIntent.getBroadcast(context, 219104, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val show = PendingIntent.getActivity(context, 219104, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(due, show), pending)
            assertTrue("System alarm clock did not arrive", latch.await(45, TimeUnit.SECONDS))
            context.filesDir.resolve("clock-delivery-proof.json").writeText(
                """{"expectedAt":$due,"deliveredAt":$delivered,"delayMs":${delivered-due},"activityOpened":false}""", Charsets.UTF_8)
            assertTrue("System alarm clock delivered too late", delivered - due in 0..2_000)
        } finally {
            manager.cancel(pending); pending.cancel(); show.cancel(); context.unregisterReceiver(receiver)
        }
    }
}
