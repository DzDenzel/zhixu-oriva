package com.planlock.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import android.os.Bundle
import com.planlock.app.api.TokenStore
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.toArgb
import com.planlock.design.InkPalettes
import com.planlock.design.PlanAlertModel
import com.planlock.design.PlanAlertSurface
import com.planlock.design.PlanLockTheme
import com.planlock.design.ThemeMode
import com.planlock.design.ThemeStyleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * REQ-044 U-R1 / CHANGE-063：锁屏时的闹钟式全屏页。提前提醒阶段有「5 分钟后再提醒」，开始阶段只有「我知道了」；
 * 任一按钮只停铃并收起（回到锁屏 / 桌面，状态栏保留胶囊），不解锁、不打开应用。画面为 core-design 的 PlanAlertSurface。
 */
class PlanAlertActivity : ComponentActivity() {
    private var themeMode = ThemeMode.SYSTEM
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        render()
        lifecycleScope.launch {
            themeMode = withContext(Dispatchers.IO) { ThemePreference(this@PlanAlertActivity).mode.first() }
            if (!isFinishing) render()
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }
    private val phase get() = runCatching { PlanAlertPhase.valueOf(intent.getStringExtra("phase").orEmpty()) }.getOrDefault(PlanAlertPhase.START)
    private fun render() {
        val owner = intent.getStringExtra("scope") ?: return finish()
        if (owner != TokenStore.state.value.dataScope) return finish()
        val id = intent.getLongExtra("plan_id", Long.MIN_VALUE)
        val startAt = intent.getLongExtra("start_at", 0)
        val until = intent.getLongExtra("until", 0)
        val now = System.currentTimeMillis()
        if (id == Long.MIN_VALUE || until <= now) return finish()
        current = java.lang.ref.WeakReference(this)
        val systemDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = ThemeStyleStore.current.isDark(themeMode, systemDark)
        val palette = InkPalettes.of(ThemeStyleStore.current, dark)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.ground.toArgb()))
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val reminder = phase == PlanAlertPhase.REMINDER
        val model = PlanAlertModel(
            label = if (reminder) "即将开始" else "计划开始了",
            countdownTo = startAt.takeIf { reminder },
            planName = intent.getStringExtra("plan_name").orEmpty(),
            detail = intent.getStringExtra("plan_detail").orEmpty(),
            quietHint = intent.getStringExtra("quiet_hint"),
            canSnooze = reminder,
        )
        val mode = themeMode
        setContent {
            PlanLockTheme(mode) {
                PlanAlertSurface(model,
                    onSnooze = {
                        PlanAlerts.snooze(this@PlanAlertActivity, owner, id, startAt)
                            ?.let { android.widget.Toast.makeText(applicationContext, it, android.widget.Toast.LENGTH_LONG).show() }
                        finish()
                    },
                    onAcknowledge = { PlanAlerts.acknowledge(this@PlanAlertActivity, owner, id); finish() })
            }
        }
        android.util.Log.i("OrivaAlerts", "FULL_SCREEN displayed phase=${phase.name} plan=$id at=${System.currentTimeMillis()}")
    }

    companion object {
        private var current = java.lang.ref.WeakReference<PlanAlertActivity>(null)
        fun close(scope: String, id: Long, phase: PlanAlertPhase) {
            current.get()?.takeIf {
                it.intent.getStringExtra("scope") == scope && it.intent.getLongExtra("plan_id", Long.MIN_VALUE) == id && it.phase == phase
            }?.let { activity -> activity.runOnUiThread { activity.finish() } }
        }
    }
}
