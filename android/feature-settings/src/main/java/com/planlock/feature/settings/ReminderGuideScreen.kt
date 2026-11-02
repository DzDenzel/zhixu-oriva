package com.planlock.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.planlock.app.api.ReadinessKey
import com.planlock.app.api.ReadinessState
import com.planlock.app.api.ReminderReadiness
import com.planlock.design.InkHue
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.PageAtmosphere
import com.planlock.design.SettingsDivider
import com.planlock.design.SettingsGroup
import com.planlock.design.SettingsHero
import com.planlock.design.SettingsItem
import com.planlock.design.SettingsStateTag
import com.planlock.design.SettingsSwitchItem
import com.planlock.design.planLockAtmosphere
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * REQ-044 U-R5：系统能力跳系统设置，「准时开始」回应用权限页，返回即刷新。
 * 数据由调用方提供（app 模块汇总 ReminderReadiness 与日历兜底），本页不直接改系统策略。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderGuideScreen(
    load: suspend () -> ReminderReadiness,
    setCalendarFallback: suspend (Boolean) -> Boolean,
    onOpenPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var readiness by remember { mutableStateOf<ReminderReadiness?>(null) }
    var calendarBusy by remember { mutableStateOf(false) }
    var calendarNote by remember { mutableStateOf<String?>(null) }
    fun refresh() { scope.launch { readiness = load() } }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun applyCalendar(on: Boolean) {
        scope.launch {
            calendarBusy = true
            try {
                val ok = setCalendarFallback(on)
                calendarNote = if (on && !ok) "没能开启：请确认已允许日历权限" else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                calendarNote = "设置没有保存，请重试"
                android.util.Log.w("OrivaReminderGuide", "Calendar fallback update failed", error)
            } finally { calendarBusy = false; readiness = load() }
        }
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) applyCalendar(true) else calendarNote = "未允许日历权限，日历兜底保持关闭"
    }
    fun requestCalendar(on: Boolean) {
        if (!on) return applyCalendar(false)
        val needed = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) applyCalendar(true)
        else calendarPermission.launch(needed)
    }
    fun open(key: ReadinessKey, intent: Intent?) {
        if (key == ReadinessKey.PUNCTUAL_START) {
            onOpenPermissions()
            return
        }
        intent ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(com.planlock.app.security.PermissionIntent.appDetails(context)) } }
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.QUIET),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent), title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                windowInsets = WindowInsets(0, 0, 0, 0))
        },
    ) { padding ->
        val current = readiness ?: return@Scaffold
        val model = reminderGuideModel(current)
        val intents = current.items.associate { it.key to it.settingsIntent }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsHero("铃", InkHue.amber, "让提醒准时响起", verdictTitle(model),
                    model.verdict != ReminderGuideVerdict.NEEDS_ACTION, verdictDetail(model))
            }
            model.deferralHeader?.let { header ->
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(header, style = MaterialTheme.typography.titleMedium, color = InkTone.text)
                        Text("「${model.deferralPlan}」· 下面的「后台运行」和「日历再提醒」能减少这种情况",
                            style = InkText.caption, color = InkTone.soft)
                    }
                }
                item { CalendarGroup(model.calendarOn, calendarBusy, calendarNote, ::requestCalendar) }
            }
            item {
                SettingsGroup {
                    model.rows.forEachIndexed { index, row ->
                        if (index > 0) SettingsDivider(inset = 14.dp)
                        val action = when (row.state) { ReadinessState.OFF -> "去开启"; ReadinessState.UNKNOWN -> "去查看"; ReadinessState.ON -> null }
                        SettingsItem(
                            title = row.title, detail = row.hint,
                            onClick = action?.let { { open(row.key, intents[row.key]) } },
                            trailing = {
                                if (action == null) SettingsStateTag(readinessStateLabel(row.state), true)
                                else Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                    SettingsStateTag(readinessStateLabel(row.state), false)
                                    TextButton(onClick = { open(row.key, intents[row.key]) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
                                }
                            },
                        )
                    }
                }
            }
            if (model.deferralHeader == null) item { CalendarGroup(model.calendarOn, calendarBusy, calendarNote, ::requestCalendar) }
            if (model.verdict != ReminderGuideVerdict.NEEDS_ACTION) item {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(20.dp)) { Text("完成") }
            }
        }
    }
}

@Composable
private fun CalendarGroup(on: Boolean, busy: Boolean, note: String?, onChange: (Boolean) -> Unit) {
    SettingsGroup {
        SettingsSwitchItem(readinessTitle(ReadinessKey.CALENDAR_FALLBACK), readinessHint(ReadinessKey.CALENDAR_FALLBACK),
            checked = on, enabled = !busy, onCheckedChange = onChange)
        note?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = InkText.caption, color = InkTone.soft) }
    }
}
