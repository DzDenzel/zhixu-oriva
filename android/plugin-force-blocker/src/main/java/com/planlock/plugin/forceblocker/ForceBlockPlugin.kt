package com.planlock.plugin.forceblocker

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.planlock.plugin.PlanLockPlugin
import com.planlock.plugin.PluginCapabilities
import com.planlock.plugin.PluginDecision
import com.planlock.plugin.PluginViewFactory
import com.planlock.plugin.PluginActionHandler
import com.planlock.plugin.PluginActionEvent
import java.io.File
import com.planlock.plugin.TimedPluginResponse

/** First external plugin: blocks every distraction already decided by the host, with no app-list logic. */
class ForceBlockPlugin internal constructor(
    private val relaxation: LimitedRelaxation,
    private val elapsedClock: () -> Long,
) : PlanLockPlugin, PluginActionHandler, TimedPluginResponse {
    constructor() : this(LimitedRelaxation(null), { SystemClock.elapsedRealtime() })
    constructor(stateDirectory: File) : this(LimitedRelaxation(File(stateDirectory, "relaxation.properties"), monotonicClock = { SystemClock.elapsedRealtime() }), { SystemClock.elapsedRealtime() })
    private val gate = ViolationEventGate()

    override fun responseDelayMillis(decision: PluginDecision): Long? =
        if (decision.shouldTrack) relaxation.remainingMillis(decision.planId, decision.packageName) else null

    override fun onAction(event: PluginActionEvent): String? = when (event.actionId) {
        "explain-blocking" -> "当前计划期间所有分心应用都会被拦截，无需单独指定。插件已启用；实际响应还需无障碍服务运行。\n${relaxation.status(event.planId)}"
        "relax-application" -> relaxation.grant(event.planId, event.packageName.orEmpty(), event.appName.orEmpty(), event.activeUntilMillis)
        else -> null
    }

    override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) {
        val observed = ExemptionDiagnostics.snapshot(decision)
        if (!RuntimeExemptionGuard.allowAction(observed)) return
        if (relaxation.isRelaxed(observed.planId, observed.packageName)) return
        if (!gate.shouldRecord(observed.planId, observed.packageName, elapsedClock())) return
        val overlay = capabilities.overlay() ?: return
        capabilities.haptic()?.perform()
        capabilities.feedback()?.emit("BLOCKED")
        val home = capabilities.home()
        overlay.show(
            factory = PluginViewFactory { context ->
                val presentation = BlockFeedbackPresentation.create(
                    decision.appName,
                    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
                )
                val density = context.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(28), dp(32), dp(28), dp(32))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(28).toFloat()
                        setColor(Color.rgb(70, 72, 74))
                    }
                    addView(ImageView(context).apply {
                        contentDescription = "${presentation.appName} 应用图标"
                        setImageDrawable(runCatching { context.packageManager.getApplicationIcon(decision.packageName) }.getOrNull())
                    }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(20) })
                    addView(TextView(context).apply {
                        text = presentation.appName
                        setTextColor(Color.WHITE)
                        textSize = 20f
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
                    addView(TextView(context).apply {
                        text = presentation.title
                        setTextColor(Color.WHITE)
                        textSize = 28f
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
                    addView(TextView(context).apply {
                        text = presentation.message
                        setTextColor(Color.rgb(220, 220, 220))
                        textSize = 16f
                        gravity = Gravity.CENTER
                    })
                }
                FrameLayout(context).apply {
                    contentDescription = "受限应用遮罩"
                    setBackgroundColor(Color.rgb(45, 47, 49))
                    addView(content, FrameLayout.LayoutParams(dp(310), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    if (presentation.animationTarget == BlockFeedbackAnimationTarget.CONTENT) {
                        ValueAnimator.ofFloat(.4f, 1f).apply {
                            duration = 240
                            addUpdateListener { content.alpha = it.animatedValue as Float }
                            start()
                        }
                    } else {
                        content.alpha = 1f
                    }
                }
            },
            displayMillis = BlockFeedbackPresentation.DISPLAY_MILLIS,
            onExpired = Runnable { home?.requestHome() },
        )
    }
}

internal data class BlockFeedbackPresentation(
    val appName: String,
    val title: String,
    val message: String,
    val displayMillis: Long,
    val animationTarget: BlockFeedbackAnimationTarget,
) {
    companion object {
        const val DISPLAY_MILLIS = 900L

        fun create(appName: String?, animatorDurationScale: Float) = BlockFeedbackPresentation(
            appName = appName?.trim().takeUnless { it.isNullOrEmpty() } ?: "此应用",
            title = "应用已锁定",
            message = "当前计划正在限制此应用",
            displayMillis = DISPLAY_MILLIS,
            animationTarget = if (animatorDurationScale > 0f) {
                BlockFeedbackAnimationTarget.CONTENT
            } else {
                BlockFeedbackAnimationTarget.NONE
            },
        )
    }
}

internal enum class BlockFeedbackAnimationTarget {
    NONE,
    CONTENT,
}

internal class ViolationEventGate(private val windowMillis: Long = 3_000) {
    private var lastPlanId: Long? = null
    private var lastPackageName: String? = null
    private var lastRecordedAt: Long = Long.MIN_VALUE

    fun shouldRecord(planId: Long, packageName: String, elapsedMillis: Long): Boolean {
        val sameAttempt = lastPlanId == planId && lastPackageName == packageName &&
            elapsedMillis - lastRecordedAt < windowMillis
        if (sameAttempt) return false
        lastPlanId = planId
        lastPackageName = packageName
        lastRecordedAt = elapsedMillis
        return true
    }
}
