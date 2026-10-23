package com.planlock.app.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.planlock.app.record.ForegroundObservation
import com.planlock.app.record.ForegroundUsageInterval
import com.planlock.app.record.ForegroundUsageIntervalTracker
import com.planlock.app.record.ObservationSource
import com.planlock.app.record.RecordEventFactory
import com.planlock.app.record.RecordStore
import com.planlock.app.security.AccessibilityHealthEvaluator
import com.planlock.app.security.AccessibilityHealthStore
import com.planlock.plugin.FeedbackCapability
import com.planlock.plugin.HapticCapability
import com.planlock.plugin.HomeCapability
import com.planlock.plugin.OverlayCapability
import com.planlock.plugin.PluginCapabilities
import com.planlock.plugin.PluginDecision
import com.planlock.plugin.PluginPlatform
import com.planlock.plugin.PluginRuntimeRegistry
import com.planlock.plugin.PluginViewFactory
import com.planlock.plugin.PluginResponseScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 无障碍前台观测器。它只采集包名和区间，不读取窗口内容，也不自行执行任何响应动作。
 */
class AccessibilityInterceptorService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val recordScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val intervals = ForegroundUsageIntervalTracker()
    private val recordFactory = RecordEventFactory()
    private lateinit var recordStore: RecordStore
    private lateinit var healthStore: AccessibilityHealthStore
    private var pluginOverlay: View? = null
    private var foregroundPackage: String? = null
    private var intervalScope: String? = null
    private val removePluginOverlay = Runnable {
        pluginOverlay?.let { view ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        }
        pluginOverlay = null
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            healthStore.markRunning()
            handler.postDelayed(this, AccessibilityHealthEvaluator.HEARTBEAT_INTERVAL_MILLIS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        recordStore = RecordStore(this)
        healthStore = AccessibilityHealthStore(this)
        handler.removeCallbacks(heartbeat)
        heartbeat.run()
        PluginRuntimeRegistry.setResponseScheduler(PluginResponseScheduler { expected, delay, onReady ->
            val callback = Runnable {
                val interactive = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
                val locked = (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
                recheckPluginResponse(expected, foregroundPackage, interactive, locked)?.let(onReady)
            }
            handler.postDelayed(callback, delay)
            AutoCloseable { handler.removeCallbacks(callback) }
        })
        recordScope.launch { PluginPlatform.get(this@AccessibilityInterceptorService).reload() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        foregroundPackage = packageName
        val now = System.currentTimeMillis()
        val currentScope = com.planlock.app.api.TokenStore.state.value.dataScope
        if (intervalScope != currentScope) {
            persist(intervals.close(now))
            intervalScope = currentScope
        }
        val decision = ExecutionEngine.inspect(packageName)
        if (decision == null) {
            persist(intervals.close(now))
            return
        }
        val shouldTrack = decision.shouldTrack
        val appName = if (shouldTrack) {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()
        } else {
            null
        }
        persist(
            intervals.observe(
                ForegroundObservation(
                    planId = decision.planId,
                    packageName = packageName,
                    appName = appName,
                    shouldTrack = shouldTrack,
                    source = ObservationSource.ACCESSIBILITY,
                ),
                now,
            )
        )
        PluginRuntimeRegistry.dispatch(
            PluginDecision(decision.planId, packageName, appName, shouldTrack),
            pluginCapabilities(decision.planId),
        )
    }

    private fun persist(interval: ForegroundUsageInterval?) {
        if (interval == null || interval.durationMillis <= 0L || !::recordStore.isInitialized) return
        val owner = intervalScope ?: return
        recordScope.launch { recordStore.forScope(owner).enqueueViolation(recordFactory.behavior(interval)) }
    }

    private fun pluginCapabilities(planId: Long): PluginCapabilities = object : PluginCapabilities {
        override fun overlay(): OverlayCapability = object : OverlayCapability {
            override fun show(factory: PluginViewFactory, displayMillis: Long, onExpired: Runnable) {
                handler.removeCallbacks(removePluginOverlay)
                removePluginOverlay.run()
                val view = factory.create(applicationContext)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
                pluginOverlay = view
                handler.postDelayed({
                    runCatching { onExpired.run() }
                    handler.postDelayed(removePluginOverlay, 120L)
                }, displayMillis.coerceIn(250L, 10_000L))
            }
        }

        override fun home(): HomeCapability = HomeCapability {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        override fun haptic(): HapticCapability = HapticCapability {
            executionHaptic(this@AccessibilityInterceptorService)
        }

        override fun feedback(): FeedbackCapability = FeedbackCapability { code ->
            val kind = runCatching { FeedbackKind.valueOf(code) }.getOrNull() ?: return@FeedbackCapability
            ExecutionFeedback.emit(planId, kind)
        }
    }

    override fun onInterrupt() = markStopped()

    override fun onUnbind(intent: Intent?): Boolean {
        persist(intervals.close(System.currentTimeMillis()))
        markStopped()
        handler.removeCallbacks(removePluginOverlay)
        removePluginOverlay.run()
        return super.onUnbind(intent)
    }

    private fun markStopped() {
        PluginRuntimeRegistry.setResponseScheduler(null)
        foregroundPackage = null
        handler.removeCallbacks(heartbeat)
        if (::healthStore.isInitialized) healthStore.markStopped()
    }

    override fun onDestroy() {
        persist(intervals.close(System.currentTimeMillis()))
        markStopped()
        recordScope.cancel()
        super.onDestroy()
    }
}
