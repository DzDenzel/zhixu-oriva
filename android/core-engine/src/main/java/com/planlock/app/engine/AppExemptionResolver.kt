package com.planlock.app.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.ContactsContract
import android.provider.AlarmClock
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

internal fun essentialAlarmPackages(resolvedPackage: String?): Set<String> = buildSet {
    resolvedPackage?.let(::add)
    add("com.android.deskclock")
    add("com.google.android.deskclock")
    add("com.coloros.alarmclock")
}

/** 严格模式 evaluate 时装配豁免集；构造不查询系统、不注册广播。 */
class AppExemptionResolver(
    private val context: Context,
    private val basicToolPackages: Set<String>,
) : AutoCloseable {
    private var facts: Map<String, PackageFacts>? = null
    private var cachedRoles: Set<String>? = null
    private var cachedBasicToolsEnabled: Boolean? = null
    private var cachedSnapshot: ExemptionAssemblySnapshot? = null
    private var generation: Long = 0
    private var registered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            synchronized(this@AppExemptionResolver) { facts = null }
        }
    }

    @Synchronized
    fun resolve(basicToolsEnabled: Boolean): Set<String> = resolveSnapshot(basicToolsEnabled).exemptPackages

    @Synchronized
    fun resolveSnapshot(basicToolsEnabled: Boolean): ExemptionAssemblySnapshot {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(packageReceiver, filter)
            }
            registered = true
        }
        val pm = context.packageManager
        // 默认桌面、启用 IME、默认通信角色可在无包广播时变化；仅调度 tick 刷新，窗口事件不查 PM。
        val roles = dynamicRoles(pm)
        val previousFacts = facts
        val currentFacts = previousFacts ?: loadFacts(pm).also { facts = it }
        if (previousFacts != null && roles == cachedRoles && basicToolsEnabled == cachedBasicToolsEnabled) {
            return checkNotNull(cachedSnapshot)
        }
        val snapshot = ExemptionAssemblySnapshot.assemble(
            generation = ++generation,
            facts = currentFacts,
            rolePackages = roles,
            basicToolsEnabled = basicToolsEnabled,
            basicToolPackages = basicToolPackages,
        )
        check(snapshot.audit().isEmpty()) { "Exemption snapshot is inconsistent: ${snapshot.audit()}" }
        cachedSnapshot = snapshot
        cachedRoles = roles
        cachedBasicToolsEnabled = basicToolsEnabled
        return snapshot
    }

    private fun loadFacts(pm: PackageManager): Map<String, PackageFacts> {
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL,
        ).mapNotNull { it.activityInfo?.packageName }.toSet()
        return pm.getInstalledApplications(0).associate { app ->
            app.packageName to PackageFacts(
                app.uid, app.flags and ApplicationInfo.FLAG_SYSTEM != 0, app.packageName in launchers,
            )
        }
    }

    internal fun loadRuntimeFacts(packageName: String): PackageFacts? =
        packageFacts(context.packageManager, packageName)

    private fun dynamicRoles(pm: PackageManager): Set<String> = buildSet {
        add("com.android.systemui")
        pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.let(::add)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.enabledInputMethodList?.forEach { add(it.packageName) }
        pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let(::add)
        addAll(essentialAlarmPackages(pm.resolveActivity(
            Intent(AlarmClock.ACTION_SHOW_ALARMS), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName))
        pm.resolveActivity(Intent(Intent.ACTION_VIEW).setType(ContactsContract.Contacts.CONTENT_TYPE), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let(::add)
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
            ?.defaultDialerPackage?.let(::add)
        Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
    }

    @Synchronized
    override fun close() {
        if (registered) context.unregisterReceiver(packageReceiver)
        registered = false
        facts = null
        cachedRoles = null
        cachedBasicToolsEnabled = null
        cachedSnapshot = null
    }
}

internal fun packageFacts(pm: PackageManager, packageName: String): PackageFacts? = runCatching {
    val app = pm.getApplicationInfo(packageName, 0)
    val hasLauncherEntry = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName),
        PackageManager.MATCH_ALL,
    ).isNotEmpty()
    PackageFacts(
        uid = app.uid,
        systemFlag = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        hasLauncherEntry = hasLauncherEntry,
    )
}.getOrNull()
