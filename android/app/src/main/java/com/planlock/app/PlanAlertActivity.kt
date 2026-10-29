package com.planlock.app

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.planlock.app.api.TokenStore
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.toArgb
import com.planlock.design.InkPalettes
import com.planlock.design.ThemeMode
import com.planlock.design.ThemeStyleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Product behaviour only; Compose styling remains owned by the UI branch. */
class PlanAlertActivity : ComponentActivity() {
    private var themeMode = ThemeMode.SYSTEM
    private var pendingDetail = false
    private var legacyUnlockReceiver: android.content.BroadcastReceiver? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT < 26) {
            legacyUnlockReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    if (pendingDetail && intent.action == android.content.Intent.ACTION_USER_PRESENT) openDetail()
                }
            }.also { registerReceiver(it, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT)) }
        }
        render()
        lifecycleScope.launch {
            themeMode = withContext(Dispatchers.IO) { ThemePreference(this@PlanAlertActivity).mode.first() }
            if (!isFinishing) render()
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }
    private fun render() {
        val owner = intent.getStringExtra("scope") ?: return finish()
        if (owner != TokenStore.state.value.dataScope) return finish()
        val id = intent.getLongExtra("plan_id", Long.MIN_VALUE)
        val end = intent.getLongExtra("end_at", 0)
        if (id == Long.MIN_VALUE || end <= System.currentTimeMillis()) return finish()
        current = java.lang.ref.WeakReference(this)
        val systemDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = ThemeStyleStore.current.isDark(themeMode, systemDark)
        val palette = InkPalettes.of(ThemeStyleStore.current, dark)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.ground.toArgb()))
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.ground.toArgb())
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding * 3, padding, padding)
            addView(TextView(this@PlanAlertActivity).apply { text = "计划开始"; textSize = 30f; setTextColor(palette.text.toArgb()) })
            addView(TextView(this@PlanAlertActivity).apply { text = intent.getStringExtra("plan_name").orEmpty(); textSize = 24f; setTextColor(palette.text.toArgb()) })
            addView(Button(this@PlanAlertActivity).apply {
                text = "5 分钟后再提醒"
                setTextColor(palette.text.toArgb())
                backgroundTintList = android.content.res.ColorStateList.valueOf(palette.raised.toArgb())
                setOnClickListener { PlanAlerts.snooze(this@PlanAlertActivity, owner, id, end); finish() }
            })
            addView(Button(this@PlanAlertActivity).apply {
                text = "我知道了"
                setTextColor(palette.text.toArgb())
                backgroundTintList = android.content.res.ColorStateList.valueOf(palette.raised.toArgb())
                setOnClickListener {
                    PlanAlerts.stopPresentation(this@PlanAlertActivity, owner, id)
                    pendingDetail = true
                    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                    if (!keyguard.isKeyguardLocked) openDetail()
                    else if (android.os.Build.VERSION.SDK_INT >= 26) {
                        keyguard.requestDismissKeyguard(this@PlanAlertActivity, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() = openDetail()
                            override fun onDismissCancelled() = finish()
                            override fun onDismissError() = finish()
                        })
                    } else {
                        @Suppress("DEPRECATION")
                        val credential = keyguard.createConfirmDeviceCredentialIntent(null, null)
                        if (credential != null) {
                            @Suppress("DEPRECATION")
                            startActivityForResult(credential, 1001)
                        } else {
                            @Suppress("DEPRECATION")
                            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                        }
                    }
                }
            })
        })
        android.util.Log.i("OrivaAlerts", "FULL_SCREEN displayed plan=$id at=${System.currentTimeMillis()}")
    }
    private fun openDetail() {
        if (!pendingDetail || isFinishing || isDestroyed) return
        pendingDetail = false
        val owner = intent.getStringExtra("scope") ?: return finish()
        startActivity(PlanAlerts.detail(this, owner, intent.getLongExtra("plan_id", Long.MIN_VALUE)))
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingDetail && android.os.Build.VERSION.SDK_INT < 26 &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) openDetail()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && pendingDetail) {
            if (resultCode == RESULT_OK) openDetail() else finish()
        }
    }

    override fun onDestroy() {
        legacyUnlockReceiver?.let { unregisterReceiver(it) }
        legacyUnlockReceiver = null
        super.onDestroy()
    }

    companion object {
        private var current = java.lang.ref.WeakReference<PlanAlertActivity>(null)
        fun close(scope: String, id: Long) {
            current.get()?.takeIf { it.intent.getStringExtra("scope") == scope && it.intent.getLongExtra("plan_id", Long.MIN_VALUE) == id }
                ?.let { activity -> activity.runOnUiThread { activity.finish() } }
        }
    }
}
