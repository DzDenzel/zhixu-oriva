package com.planlock.feature.settings

import com.planlock.design.Ink
import com.planlock.design.InkHue
import com.planlock.design.color
import com.planlock.design.tint
import com.planlock.design.InkMasthead
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.inkReveal
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.planlock.app.sync.AppSync
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.planlock.app.api.ApiClient
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.TokenStore
import com.planlock.app.api.WeatherStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.security.BackupPasswordStore
import com.planlock.app.security.PermissionChecker
import com.planlock.app.security.BasePermissionSummary
import com.planlock.app.security.basePermissionSummary
import com.planlock.design.PageAtmosphere
import com.planlock.design.ThemeMode
import com.planlock.design.planLockAtmosphere
import com.planlock.plugin.PluginPlatform
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SettingsDomain(val route: String, val title: String) {
    THEME("theme", "主题"),
    BASIC_TOOLS("basic-tools", "基础工具"),
    PLUGINS("plugins", "插件"),
    WEATHER("weather", "天气城市"),
    ACCOUNT("account", "账户"),
    CONNECTION("connection", "后端连接"),
    PASSWORD("password", "备用密码"),
    DEVICES("devices", "设备"),
}

internal enum class SettingsOverviewItem(val domain: SettingsDomain?, val title: String) {
    THEME(SettingsDomain.THEME, SettingsDomain.THEME.title),
    BASIC_TOOLS(SettingsDomain.BASIC_TOOLS, SettingsDomain.BASIC_TOOLS.title),
    PLUGINS(SettingsDomain.PLUGINS, SettingsDomain.PLUGINS.title),
    WEATHER(SettingsDomain.WEATHER, SettingsDomain.WEATHER.title),
    ACCOUNT(SettingsDomain.ACCOUNT, SettingsDomain.ACCOUNT.title),
    CONNECTION(SettingsDomain.CONNECTION, SettingsDomain.CONNECTION.title),
    DEVICES(SettingsDomain.DEVICES, SettingsDomain.DEVICES.title),
    PASSWORD(SettingsDomain.PASSWORD, SettingsDomain.PASSWORD.title),
    PERMISSIONS(null, "权限与系统能力"),
}

internal data class SettingsOverviewSection(
    val title: String,
    val items: List<SettingsOverviewItem>,
)

internal object SettingsOverviewStructure {
    val sections = listOf(
        SettingsOverviewSection("外观", listOf(SettingsOverviewItem.THEME)),
        SettingsOverviewSection(
            "功能",
            listOf(SettingsOverviewItem.BASIC_TOOLS, SettingsOverviewItem.PLUGINS, SettingsOverviewItem.WEATHER),
        ),
        SettingsOverviewSection("数据与账户", listOf(SettingsOverviewItem.ACCOUNT, SettingsOverviewItem.CONNECTION, SettingsOverviewItem.DEVICES)),
        SettingsOverviewSection(
            "安全与系统",
            listOf(SettingsOverviewItem.PASSWORD, SettingsOverviewItem.PERMISSIONS),
        ),
    )
}

private data class SettingsSummary(
    val basicToolsEnabled: Boolean = true,
    val pluginSummary: String = "总开关关闭 · 安全模式开启",
    val weather: String = "未设置",
    val password: String = "未设置",
    val actionGate: Boolean = false,
    val deviceCount: Int? = null,
    val permissions: BasePermissionSummary = basePermissionSummary(emptyMap()),
)

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onOpenDomain: (SettingsDomain) -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val sync = remember { AppSync.get(context) }
    val syncState by sync.coordinator.state.collectAsState()
    val authState by TokenStore.state.collectAsState()
    val connection by com.planlock.app.api.ConnectionStore.state.collectAsState()
    LaunchedEffect(Unit) {
        while (true) { TokenStore.refreshLocalValidity(); kotlinx.coroutines.delay(1_000) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(SettingsSummary()) }

    suspend fun refresh() {
        val preferences = context.planLockPreferences.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.first()
        val pluginState = PluginPlatform.get(context).snapshot()
        val weather = WeatherStore(context.planLockPreferences).location()
        val password = BackupPasswordStore(context.planLockPreferences).hasPassword()
        val permissions = PermissionChecker.check(context)
        // 与设备子页同源：同一份缓存的设备列表长度；未登录 / 已退出时不计数（v4.1）。
        val devices = if (settingsDevicesManageable(TokenStore.state.value))
            runCatching { sync.cache.read(TokenStore.userId).devices.orEmpty().size }.getOrNull() else -1
        summary = SettingsSummary(
            basicToolsEnabled = preferences[PlanLockPreferenceKeys.basicToolsEnabled] ?: true,
            pluginSummary = "总开关${if (pluginState.globalEnabled) "开启" else "关闭"} · 安全模式${if (pluginState.safeMode) "开启" else "关闭"} · ${pluginState.installed.size} 个已安装",
            weather = weather?.displayName ?: "未设置时今日页不显示天气",
            password = if (password) "已设置" else "未设置",
            actionGate = preferences[PlanLockPreferenceKeys.actionGateEnabled] ?: false,
            deviceCount = devices,
            permissions = basePermissionSummary(permissions),
        )
    }

    LaunchedEffect(syncState.revision, authState.signedOut, authState.session.signedIn) { refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.QUIET),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                InkMasthead(eyebrow = "", title = "设置",
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp, start = 6.dp, end = 6.dp).inkReveal(0))
            }
            SettingsOverviewStructure.sections.forEachIndexed { sectionIndex, section ->
                item {
                    Text(section.title, style = InkText.meta, color = InkTone.faint,
                        modifier = Modifier.padding(top = 14.dp, start = 6.dp).inkReveal(sectionIndex + 1))
                }
                item {
                    OverviewSection(
                        section = section,
                        summaryFor = { item ->
                            when (item) {
                                SettingsOverviewItem.THEME -> com.planlock.design.ThemeStyleStore.current.let { if (it == com.planlock.design.ThemeStyle.INK) "墨序 · " + themeMode.label else it.label }
                                SettingsOverviewItem.BASIC_TOOLS -> if (summary.basicToolsEnabled) "默认不计入分心" else "按计划名单判断"
                                SettingsOverviewItem.PLUGINS -> summary.pluginSummary
                                SettingsOverviewItem.WEATHER -> summary.weather
                                SettingsOverviewItem.ACCOUNT -> accountPresentation(authState).let {
                                    if (authState.ownershipMismatch) it.description else it.title
                                }
                                SettingsOverviewItem.DEVICES -> when (val count = summary.deviceCount) {
                                    null -> "读取失败，可进入重试"
                                    -1 -> "未登录 · 登录后可管理"
                                    else -> "$count 台设备"
                                }
                                SettingsOverviewItem.CONNECTION -> connection.config?.instanceLabel ?: "仅本地使用"
                                SettingsOverviewItem.PASSWORD -> summary.password + if (summary.actionGate) " · 执行期确认开启" else " · 执行期确认关闭"
                                SettingsOverviewItem.PERMISSIONS -> "${summary.permissions.enabled}/${summary.permissions.total} 已开启"
                            }
                        },
                        onOpen = { item ->
                            item.domain?.let(onOpenDomain) ?: onOpenPermissions()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewSection(
    section: SettingsOverviewSection,
    summaryFor: (SettingsOverviewItem) -> String,
    onOpen: (SettingsOverviewItem) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = InkTone.raised,
    ) {
        Column {
            section.items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 62.dp, end = 16.dp),
                        color = InkTone.hairline.copy(alpha = .6f),
                    )
                }
                val mark = settingsMark(item)
                com.planlock.design.SettingsItem(item.title, summaryFor(item), mark.glyph, mark.hue, onClick = { onOpen(item) })
            }
        }
    }
}
