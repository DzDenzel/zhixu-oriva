package com.planlock.feature.settings

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
    PASSWORD("password", "备用密码"),
    DEVICES("devices", "设备"),
}

internal enum class SettingsOverviewItem(val domain: SettingsDomain?, val title: String) {
    THEME(SettingsDomain.THEME, SettingsDomain.THEME.title),
    BASIC_TOOLS(SettingsDomain.BASIC_TOOLS, SettingsDomain.BASIC_TOOLS.title),
    PLUGINS(SettingsDomain.PLUGINS, SettingsDomain.PLUGINS.title),
    WEATHER(SettingsDomain.WEATHER, SettingsDomain.WEATHER.title),
    ACCOUNT(SettingsDomain.ACCOUNT, SettingsDomain.ACCOUNT.title),
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
        SettingsOverviewSection("数据与账户", listOf(SettingsOverviewItem.ACCOUNT, SettingsOverviewItem.DEVICES)),
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
        val devices = if (TokenStore.signedIn) runCatching { sync.cache.read(TokenStore.userId).deviceCount }.getOrNull() else 0
        summary = SettingsSummary(
            basicToolsEnabled = preferences[PlanLockPreferenceKeys.basicToolsEnabled] ?: true,
            pluginSummary = "总开关${if (pluginState.globalEnabled) "开启" else "关闭"} · 安全模式${if (pluginState.safeMode) "开启" else "关闭"} · ${pluginState.installed.size} 个已安装",
            weather = weather?.displayName ?: "未设置时今日页不显示天气",
            password = if (password) "已设置，可作为可选确认方式" else "未设置，执行期确认未开启",
            deviceCount = devices,
            permissions = basePermissionSummary(permissions),
        )
    }

    LaunchedEffect(syncState.revision) { refresh() }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp)) {
                    Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                }
            }
            SettingsOverviewStructure.sections.forEach { section ->
                item {
                    Text(
                        section.title,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item {
                    OverviewSection(
                        section = section,
                        summaryFor = { item ->
                            when (item) {
                                SettingsOverviewItem.THEME -> themeMode.label
                                SettingsOverviewItem.BASIC_TOOLS -> if (summary.basicToolsEnabled) "默认不计入分心" else "按计划名单判断"
                                SettingsOverviewItem.PLUGINS -> summary.pluginSummary
                                SettingsOverviewItem.WEATHER -> summary.weather
                                SettingsOverviewItem.ACCOUNT -> accountPresentation(authState).let {
                                    if (authState.ownershipMismatch) it.description else it.title
                                }
                                SettingsOverviewItem.DEVICES -> summary.deviceCount?.let { "$it 台设备" } ?: "读取失败，可进入重试"
                                SettingsOverviewItem.PASSWORD -> summary.password
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
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            section.items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                OverviewRow(item.title, summaryFor(item)) { onOpen(item) }
            }
        }
    }
}

@Composable
private fun OverviewRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "进入")
    }
}
