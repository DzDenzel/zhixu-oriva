package com.planlock.app.security

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

enum class Capability(val title: String, val explanation: String) {
    DND("免打扰", "执行计划时仅保留闹钟，需要系统的勿扰访问权限。"),
    NOTIFICATION_SILENCE("通知静默", "执行计划时向系统请求减少通知音效，需要通知使用权；不读取或上传通知内容。"),
    NOTIFICATIONS("通知发送", "用于计划状态和日程提醒，需要允许知序发送通知。"),
    EXACT_ALARM("精确闹钟", "用于按设定时间提醒；未授权时系统可能延迟提醒。"),
    LOCATION("当前位置", "用系统粗略位置获取天气和地图中心；拒绝后可手动选择地点。"),
}

data class CapabilityRequest(
    val capability: Capability,
    val titleKey: String,
    val explanationKey: String,
    val settingsIntent: Intent,
    val runtimePermissions: List<String> = emptyList(),
)

enum class DndAccessSource { NONE, DIRECT, NOTIFICATION_LISTENER }

internal fun dndAccessSource(effective: Boolean, direct: Boolean, listener: Boolean): DndAccessSource = when {
    !effective -> DndAccessSource.NONE
    !direct && listener -> DndAccessSource.NOTIFICATION_LISTENER
    else -> DndAccessSource.DIRECT
}

object CapabilityRequirements {
    fun reminder(enabled: Boolean): Set<Capability> = if (enabled) setOf(Capability.NOTIFICATIONS, Capability.EXACT_ALARM) else emptySet()
    fun plan(autoDnd: Boolean?, notifySilence: Boolean?, reminder: Boolean, punctualStart: Boolean = false) = buildSet {
        if (autoDnd == true) add(Capability.DND)
        if (notifySilence == true) add(Capability.NOTIFICATION_SILENCE)
        addAll(reminder(reminder))
        if (punctualStart) add(Capability.EXACT_ALARM)
    }
    fun missing(required: Set<Capability>, granted: Set<Capability>) = required - granted
}

fun capabilityEffectText(enabled: Boolean?, granted: Boolean): String = when {
    enabled == null -> "未配置"
    !enabled -> "未开启"
    !granted -> "未生效：未授权"
    else -> "已开启"
}

/** Process-safe capability facts. Observation refreshes on resume and retains no Activity. */
class CapabilityAccess(context: Context) {
    private val app = context.applicationContext
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    fun check(capability: Capability): Boolean = granted(app, capability)
    fun refresh() { mutable.value = read() }
    fun dndSource(): DndAccessSource {
        val direct = runCatching { Settings.Secure.getString(app.contentResolver, "enabled_notification_policy_access_packages")
            .orEmpty().split(':').contains(app.packageName) }.getOrDefault(false)
        return dndAccessSource(check(Capability.DND), direct, check(Capability.NOTIFICATION_SILENCE))
    }
    private fun read() = Capability.entries.associateWith { granted(app, it) }

    fun request(capability: Capability): CapabilityRequest {
        val packageUri = Uri.parse("package:${app.packageName}")
        val intent = when (capability) {
            Capability.DND -> {
                // Some OEM lists omit sideloaded apps. Prefer the system's package-specific
                // settings page when it is available; retain the public list as fallback.
                val detail = Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS", packageUri)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                if (detail.resolveActivity(app.packageManager) != null) detail
                else Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
            Capability.NOTIFICATION_SILENCE -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            Capability.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            Capability.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri) else PermissionIntent.appDetails(app)
            Capability.LOCATION -> if (hasLocationPermission(app) && !locationEnabled(app)) Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) else PermissionIntent.appDetails(app)
        }
        val runtime = when (capability) {
            Capability.LOCATION -> if (hasLocationPermission(app)) emptyList() else listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            Capability.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            else -> emptyList()
        }
        return CapabilityRequest(capability, "capability.${capability.name.lowercase()}.title", "capability.${capability.name.lowercase()}.reason", intent, runtime)
    }

    fun observe(onResume: () -> Unit = {}): Closeable {
        val application = app as Application
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { refresh(); onResume() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(observer)
        refresh()
        return Closeable { application.unregisterActivityLifecycleCallbacks(observer) }
    }

    companion object {
        private fun hasLocationPermission(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        private fun locationEnabled(context: Context): Boolean {
            val manager = context.getSystemService(LocationManager::class.java) ?: return false
            return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
        fun granted(context: Context, capability: Capability): Boolean = when (capability) {
            Capability.DND -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
            Capability.NOTIFICATION_SILENCE -> {
                val expected = ComponentName(context.packageName, "com.planlock.app.FocusNotificationListenerService").flattenToString()
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().split(':').any { it.equals(expected, true) }
            }
            Capability.NOTIFICATIONS -> context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            Capability.EXACT_ALARM -> Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            Capability.LOCATION -> hasLocationPermission(context) && locationEnabled(context)
        }
    }
}
