package com.planlock.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.planlock.app.api.TokenStore

/** Product behaviour only; Compose styling remains owned by the UI branch. */
class PlanAlertActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        render()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }
    private fun render() {
        val owner = intent.getStringExtra("scope") ?: return finish()
        if (owner != TokenStore.state.value.dataScope) return finish()
        val id = intent.getLongExtra("plan_id", Long.MIN_VALUE)
        val end = intent.getLongExtra("end_at", 0)
        if (id == Long.MIN_VALUE || end <= System.currentTimeMillis()) return finish()
        current = java.lang.ref.WeakReference(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding * 3, padding, padding)
            addView(TextView(this@PlanAlertActivity).apply { text = "计划开始"; textSize = 30f })
            addView(TextView(this@PlanAlertActivity).apply { text = intent.getStringExtra("plan_name").orEmpty(); textSize = 24f })
            addView(Button(this@PlanAlertActivity).apply {
                text = "5 分钟后再提醒"
                setOnClickListener { PlanAlerts.snooze(this@PlanAlertActivity, owner, id, end); finish() }
            })
            addView(Button(this@PlanAlertActivity).apply {
                text = "我知道了"
                setOnClickListener {
                    PlanAlerts.acknowledge(this@PlanAlertActivity, owner, id)
                    startActivity(PlanAlerts.detail(this@PlanAlertActivity, owner, id))
                    finish()
                }
            })
        })
        android.util.Log.i("OrivaAlerts", "FULL_SCREEN displayed plan=$id at=${System.currentTimeMillis()}")
    }
    companion object {
        private var current = java.lang.ref.WeakReference<PlanAlertActivity>(null)
        fun close(scope: String, id: Long) {
            current.get()?.takeIf { it.intent.getStringExtra("scope") == scope && it.intent.getLongExtra("plan_id", Long.MIN_VALUE) == id }
                ?.let { activity -> activity.runOnUiThread { activity.finish() } }
        }
    }
}
