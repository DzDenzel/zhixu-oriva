package com.planlock.feature.settings

import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.planlock.design.ChoiceChips
import com.planlock.design.PresentationCard
import com.planlock.design.SectionHeading
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.planlock.app.sync.AppSync
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.TokenStore
import com.planlock.app.api.AuthSession
import com.planlock.app.api.LoginAttemptGuard
import com.planlock.app.api.planLockPreferences
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.WeatherClient
import com.planlock.app.api.WeatherLocation
import com.planlock.app.api.WeatherStore
import kotlinx.coroutines.CancellationException
import com.planlock.app.api.dto.BackupPasswordResetRequest
import com.planlock.app.api.dto.BackupPasswordSetRequest
import com.planlock.app.api.dto.DeviceDto
import com.planlock.app.api.dto.LoginRequest
import com.planlock.app.api.dto.MergeRequest
import com.planlock.app.api.dto.SendCodeRequest
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.planlock.design.PersistentStatus
import com.planlock.design.StatusCard
import com.planlock.design.StatusActionRow
import com.planlock.design.ThemeMode
import com.planlock.design.SettingSwitch
import com.planlock.design.planLockAtmosphere
import com.planlock.design.PageAtmosphere
import com.planlock.design.SettingsCaption
import com.planlock.design.SettingsDivider
import com.planlock.design.SettingsGroup
import com.planlock.design.SettingsHero
import com.planlock.design.SettingsItem
import com.planlock.design.SettingsStateTag
import com.planlock.design.SettingsSwitchItem
import com.planlock.app.security.BackupPasswordStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.IOException

private enum class Dialog { LOGIN, SET_PASSWORD, RESET_PASSWORD, WEATHER_CITY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailScreen(
    domain: SettingsDomain,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val sync = remember { AppSync.get(context) }
    val syncState by sync.coordinator.state.collectAsState()
    val passwordStore = remember { BackupPasswordStore(context.planLockPreferences) }
    val weatherStore = remember { WeatherStore(context.planLockPreferences) }
    val weatherClient = remember { WeatherClient() }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
    var loadingDevices by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    val loginAttempts = remember { LoginAttemptGuard() }
    val loginSubmitting by loginAttempts.submitting.collectAsState()
    var loginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(dialog) { dialogError = null }
    val snackbar = remember { SnackbarHostState() }
    var deviceReadError by remember { mutableStateOf<String?>(null) }
    var preferencesReadError by remember { mutableStateOf<String?>(null) }
    fun feedback(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    val authState by TokenStore.state.collectAsState()
    val accountUi = accountPresentation(authState)
    LaunchedEffect(Unit) {
        while (true) { TokenStore.refreshLocalValidity(); delay(1_000) }
    }
    var basicToolsEnabled by remember { mutableStateOf(true) }
    var actionGateEnabled by remember { mutableStateOf(false) }
    var weatherLocation by remember { mutableStateOf<WeatherLocation?>(null) }
    var parkedCity by remember { mutableStateOf(ParkedCity.get(context)) }
    // U5（v4.3）：天气城市二选一——当前位置（系统定位）或手动城市；手动优先（WeatherLocationChoice）
    val locationGate = com.planlock.design.rememberCapabilityPermissionGate()
    val located by com.planlock.app.api.SystemLocationProvider.get(context).state.collectAsState()
    LaunchedEffect(domain, weatherLocation) {
        if (domain == SettingsDomain.WEATHER && weatherLocation == null) runCatching { com.planlock.app.api.SystemLocationProvider.get(context).resolve() }
    }
    var hasPassword by remember { mutableStateOf<Boolean?>(null) }
    // 设备只在「已登录且未退出」时可管理；退出后不展示上一个账号残留的设备快照（v4.1）。
    val canManageDevices = settingsDevicesManageable(authState)

    suspend fun loadDevices() {
        loadingDevices = true
        if (!settingsDevicesManageable(TokenStore.state.value)) {
            devices = emptyList(); deviceReadError = null; loadingDevices = false
            return
        }
        try {
            devices = sync.cache.read(TokenStore.userId).devices.orEmpty()
            deviceReadError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { deviceReadError = "本机设备快照读取失败，可重试同步" }
        loadingDevices = false
    }
    LaunchedEffect(syncState.revision, canManageDevices) {
        loadDevices()
        weatherLocation = weatherStore.location()
        hasPassword = runCatching { passwordStore.hasPassword() }.getOrDefault(false)
    }
    LaunchedEffect(context) {
        try {
            context.planLockPreferences.data.collect { preferences ->
                basicToolsEnabled = preferences[PlanLockPreferenceKeys.basicToolsEnabled] ?: true
                actionGateEnabled = preferences[PlanLockPreferenceKeys.actionGateEnabled] ?: false
            }
        } catch (_: IOException) {
            preferencesReadError = "基础工具设置读取失败，请重新打开设置页"
        }
    }

    val guestOffer by sync.guestMergeOffer.collectAsState()
    guestOffer?.let { offer ->
        AlertDialog(onDismissRequest = { scope.launch { sync.chooseGuestMerge(false) } },
            title = { Text("未登录时的内容") },
            text = { Text("把未登录时的 ${offer.count} 条内容合并到此账号？") },
            confirmButton = { TextButton(onClick = { scope.launch {
                try { sync.chooseGuestMerge(true); sync.localChangesCommitted() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback("合并未完成，游客内容已保留") }
            } }) { Text("合并到此账号") } },
            dismissButton = { TextButton(onClick = { scope.launch { sync.chooseGuestMerge(false) } }) { Text("保留在未登录") } })
    }

    dialog?.let { active ->
        when (active) {
            Dialog.LOGIN -> LoginDialog(
                error = dialogError,
                submitting = loginSubmitting,
                onDismiss = { loginAttempts.invalidate(); loginJob?.cancel(); dialog = null },
                sendCode = { account ->
                    runCatching {
                        val result = api.sendCode(SendCodeRequest(account, "login"))
                        if (result.code == 0) null else result.message
                    }.getOrElse { "验证码发送失败，请检查网络" }
                },
                onSubmit = loginSubmit@{ account, credential, useCode ->
                    if (loginJob?.isActive == true) return@loginSubmit
                    val attempt = loginAttempts.begin()
                    dialogError = null
                    loginJob = scope.launch {
                        val loginConnection = com.planlock.app.api.ConnectionStore.state.value
                        try {
                            val deviceUid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-dev"
                            val result = try {
                                api.login(
                                    LoginRequest(
                                        account,
                                        password = credential.takeUnless { useCode },
                                        code = credential.takeIf { useCode },
                                        deviceUid = deviceUid,
                                        deviceName = "Android",
                                        platform = "android"
                                    )
                                )
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            val auth = result?.data
                            if (result?.code == 0 && auth?.token != null) {
                                try {
                                    if (!loginAttempts.isCurrent(attempt)) throw CancellationException("登录操作已取消")
                                    val merged = sync.commitLogin(auth, loginConnection)
                                    feedback(if (merged != null) "登录成功，已合并 $merged 条本机内容" else "登录成功")
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) {
                                    dialogError = "登录身份保存失败，请重试"
                                    return@launch
                                }
                                dialog = null
                                sync.coordinator.refresh()
                                loadDevices()
                            } else dialogError = result?.message ?: "登录失败，请稍后重试"
                        } finally {
                            loginAttempts.complete(attempt)
                        }
                    }
                }
            )
            Dialog.SET_PASSWORD -> SetPasswordDialog(
                error = dialogError,
                onDismiss = { dialog = null },
                onSubmit = { password, question, answer ->
                    scope.launch {
                        val ok = runCatching { api.setBackupPassword(BackupPasswordSetRequest(password, question, answer)).code == 0 }.getOrDefault(false)
                        if (ok) passwordStore.set(password)
                        if (ok) hasPassword = true
                        if (ok) feedback("备用密码已设置") else dialogError = "设置失败，请检查输入或网络"
                        if (ok) dialog = null
                    }
                }
            )
            Dialog.RESET_PASSWORD -> ResetPasswordDialog(
                error = dialogError,
                onDismiss = { dialog = null },
                onSubmit = { account, credential, useCode, password ->
                    scope.launch {
                        val request = BackupPasswordResetRequest(account, code = credential.takeIf { useCode }, securityAnswer = credential.takeUnless { useCode }, newBackupPassword = password)
                        val ok = runCatching { api.resetBackupPassword(request).code == 0 }.getOrDefault(false)
                        if (ok) passwordStore.set(password)
                        if (ok) hasPassword = true
                        if (ok) feedback("备用密码已重置") else dialogError = "重置失败，请检查验证码或密保答案"
                        if (ok) dialog = null
                    }
                }
            )
            Dialog.WEATHER_CITY -> WeatherCitySearchPage(
                search = weatherClient::searchCities,
                onDismiss = { dialog = null },
                onSelect = { location ->
                    scope.launch {
                        weatherStore.saveLocation(location)
                        sync.weatherLocationChanged()
                        weatherLocation = location
                        ParkedCity.clear(context); parkedCity = null
                        feedback("天气城市已设为 ${location.displayName.substringBefore(" · ")}")
                        dialog = null
                    }
                },
            )
        }
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.QUIET),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { com.planlock.design.InkSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent), title = {},
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val mark = settingsMark(domain)
            item {
                when (domain) {
                    SettingsDomain.THEME -> com.planlock.design.ThemeStyleStore.current.let { style ->
                        SettingsHero(mark.glyph, mark.hue, domain.title, style.label, true, style.motto)
                    }
                    SettingsDomain.BASIC_TOOLS -> SettingsHero(mark.glyph, mark.hue, domain.title,
                        if (basicToolsEnabled) "已开启" else "已关闭", basicToolsEnabled, "常用工具不计入分心")
                    SettingsDomain.WEATHER -> SettingsHero(mark.glyph, mark.hue, domain.title,
                        when { weatherLocation != null -> "手动"; located.location != null -> "当前位置"; else -> "未设置" },
                        weatherLocation != null || located.location != null,
                        // v4.4：地点已在下方卡片里写全，头部不再重复（且等宽字体显示地名很乱）
                        if (weatherLocation != null || located.location != null) null else "未设置时今日页不显示天气")
                    SettingsDomain.ACCOUNT -> SettingsHero(mark.glyph, mark.hue, domain.title, accountUi.title,
                        authState.session.signedIn && !authState.signedOut && !authState.expired && !authState.ownershipMismatch,
                        accountUi.description)
                    SettingsDomain.PASSWORD -> SettingsHero(mark.glyph, mark.hue, domain.title,
                        when (hasPassword) { null -> "读取中"; true -> "已设置"; false -> "未设置" }, hasPassword == true,
                        if (actionGateEnabled) "执行期确认已开启" else "执行期确认未开启")
                    SettingsDomain.DEVICES -> SettingsHero(mark.glyph, mark.hue, domain.title,
                        when { !canManageDevices -> "未登录"; loadingDevices -> "读取中"; else -> "${devices.size} 台" },
                        canManageDevices, if (canManageDevices) "已关联到当前账号的设备" else "登录后可查看和管理")
                    SettingsDomain.PLUGINS, SettingsDomain.CONNECTION -> SettingsHero(mark.glyph, mark.hue, domain.title)
                }
            }
            when (domain) {
                SettingsDomain.THEME -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsCaption("主题")
                        SettingsGroup {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                com.planlock.design.ThemeStyleCards(inkDark = themeMode.isDark(androidx.compose.foundation.isSystemInDarkTheme()))
                                Text("每套主题有自己的底色、字体与背景；切换时颜色渐变，不会重新加载页面。",
                                    style = com.planlock.design.InkText.meta, color = com.planlock.design.InkTone.faint)
                            }
                        }
                        }
                    }
                    if (com.planlock.design.ThemeStyleStore.current == com.planlock.design.ThemeStyle.INK) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SettingsCaption("墨序 · 明暗")
                            SettingsGroup {
                                Column(Modifier.padding(14.dp)) { com.planlock.design.ThemeSwatches(themeMode, onThemeModeChange) }
                            }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SettingsCaption("墨序 · 背景")
                            SettingsGroup {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    com.planlock.design.BackdropSwatches()
                                    Text("背景铺在整个应用之下，切换页面时保持不动；晨雾会缓慢飘移，系统减弱动画时静止。",
                                        style = com.planlock.design.InkText.meta, color = com.planlock.design.InkTone.faint)
                                }
                            }
                            }
                        }
                    }
                }
                SettingsDomain.BASIC_TOOLS -> item {
                    SettingsGroup {
                        SettingsSwitchItem("基础工具默认不计入分心", "保留常用工具；系统必要功能始终不计入分心", basicToolsEnabled) { enabled ->
                            scope.launch {
                                try {
                                    context.planLockPreferences.edit { it[PlanLockPreferenceKeys.basicToolsEnabled] = enabled }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    feedback("基础工具设置保存失败，请重试")
                                }
                            }
                        }
                    }
                }
                SettingsDomain.WEATHER -> item {
                    // v4.3：天气应用式的城市列表（WeatherSettings.kt）
                    WeatherLocationsSection(
                        manual = weatherLocation,
                        parked = parkedCity,
                        located = located,
                        onUseMyLocation = {
                            // 先申请定位；授权后改用定位，手动城市停放在列表里（v4.4：不再删除）
                            locationGate.request(com.planlock.app.security.Capability.LOCATION) { granted ->
                                if (granted) scope.launch {
                                    weatherLocation?.let { ParkedCity.put(context, it); parkedCity = it }
                                    if (weatherLocation != null) {
                                        weatherStore.clearLocation()
                                        sync.weatherLocationChanged()
                                        weatherLocation = null
                                    }
                                    val place = com.planlock.app.api.SystemLocationProvider.get(context).resolve(force = true)
                                    feedback(if (place != null) "天气将跟随当前位置：${place.displayName.substringBefore(" · ")}" else "暂未获取当前位置，可稍后再试或添加城市")
                                }
                            }
                        },
                        onAddCity = { dialog = Dialog.WEATHER_CITY },
                        onActivateCity = { city ->
                            scope.launch {
                                weatherStore.saveLocation(city)
                                sync.weatherLocationChanged()
                                weatherLocation = city
                                ParkedCity.clear(context); parkedCity = null
                                feedback("今日页改为显示 ${city.displayName.substringBefore(" · ")} 的天气")
                            }
                        },
                        onRemoveCity = {
                            scope.launch {
                                val wasActive = weatherLocation != null
                                if (wasActive) {
                                    weatherStore.clearLocation()
                                    sync.weatherLocationChanged()
                                    weatherLocation = null
                                }
                                ParkedCity.clear(context); parkedCity = null
                                feedback(if (wasActive) "已移除手动城市，改为跟随当前位置" else "已移除手动城市")
                            }
                        },
                    )
                }
                SettingsDomain.ACCOUNT -> item {
                    if (accountUi.loginLabel.isNotEmpty() || accountUi.showSignOut) SettingsGroup {
                        if (accountUi.loginLabel.isNotEmpty()) {
                            SettingsItem(accountUi.loginLabel, "手机号或邮箱，密码或验证码", onClick = { dialog = Dialog.LOGIN })
                        }
                        if (accountUi.loginLabel.isNotEmpty() && accountUi.showSignOut) SettingsDivider(inset = 14.dp)
                        if (accountUi.showSignOut) {
                            SettingsItem("退出登录", "本地计划与记录会保留", titleColor = MaterialTheme.colorScheme.error, onClick = {
                                loginAttempts.invalidate()
                                loginJob?.cancel()
                                scope.launch {
                                    try {
                                        sync.withSessionChange { TokenStore.signOut(context.planLockPreferences) }
                                        feedback("已退出登录，本地数据已保留")
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { feedback("退出登录保存失败，请重试") }
                                }
                            })
                        }
                    }
                }
                SettingsDomain.PASSWORD -> {
                    item {
                        SettingsGroup {
                            SettingsSwitchItem(
                                "执行期动作确认",
                                if (hasPassword == false) "需先设置备用密码；开启后，执行期间暂停、编辑或删除计划需要输入"
                                else "开启后，执行期间暂停、编辑或删除计划需要备用密码",
                                actionGateEnabled,
                            ) { enabled ->
                                scope.launch {
                                    runCatching { context.planLockPreferences.edit { it[PlanLockPreferenceKeys.actionGateEnabled] = enabled } }
                                        .onFailure { feedback("动作确认设置保存失败，请重试") }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsCaption("密码")
                        SettingsGroup {
                            if (hasPassword == true) {
                                SettingsItem("修改备用密码", "设置一个新的 4–16 位密码", onClick = { dialog = Dialog.SET_PASSWORD })
                                SettingsDivider(inset = 14.dp)
                                SettingsItem("忘记密码", "通过验证码或密保问题重置", onClick = { dialog = Dialog.RESET_PASSWORD })
                            } else {
                                SettingsItem("设置备用密码", "4–16 位，用于敏感操作确认", onClick = { dialog = Dialog.SET_PASSWORD })
                                SettingsDivider(inset = 14.dp)
                                SettingsItem("已在其他设备设置过", "通过验证码或密保问题重置后在本机使用", onClick = { dialog = Dialog.RESET_PASSWORD })
                            }
                        }
                        }
                    }
                }
                SettingsDomain.DEVICES -> {
                    if (!canManageDevices) item {
                        SettingsGroup {
                            SettingsItem("登录账号", "登录并联网后可查看和管理已关联设备", onClick = { dialog = Dialog.LOGIN })
                        }
                    } else {
                        item { SettingsCaption(if (loadingDevices) "正在读取设备…" else "已关联设备") }
                        if (!loadingDevices && devices.isEmpty()) item {
                            SettingsGroup { SettingsItem("暂无可管理设备", "联网同步后会显示在这里") }
                        }
                        if (!loadingDevices) items(devices, key = { it.id }) { device ->
                            DeviceRow(
                                device,
                                onPrimary = { scope.launch { runCatching { api.setPrimaryDevice(device.id) }; sync.coordinator.refresh()?.join(); loadDevices() } },
                                onUnbind = { scope.launch { runCatching { api.unbindDevice(device.id) }; sync.coordinator.refresh()?.join(); loadDevices() } },
                            )
                        }
                    }
                }
                SettingsDomain.PLUGINS, SettingsDomain.CONNECTION -> Unit
            }
            (if (domain == SettingsDomain.DEVICES) deviceReadError else preferencesReadError)?.let {
                item { PersistentStatus("设置读取异常 · 详情", it) }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, status: String? = null) {
    SectionHeading(title, status)
}

@Composable
private fun CompactInfoRow(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PermissionSettingRow(
    title: String,
    description: String,
    status: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(status, style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    PresentationCard(content = content)
}

@Composable
private fun DeviceRow(device: DeviceDto, onPrimary: () -> Unit, onUnbind: () -> Unit) {
    val online = device.online == true
    val primary = device.isPrimary == true
    SettingsGroup {
        SettingsItem(
            title = device.deviceName ?: "未命名设备",
            detail = devicePlatformLabel(device.platform) + if (primary) " · 主设备" else "",
            glyph = if (primary) "主" else "机",
            hue = if (primary) com.planlock.design.InkHue.seal else com.planlock.design.InkHue.amber,
            trailing = { SettingsStateTag(if (online) "在线" else "离线", online) },
        )
        SettingsDivider(inset = 14.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
            if (!primary) TextButton(onClick = onPrimary, modifier = Modifier.heightIn(min = 48.dp)) { Text("设为主设备") }
            TextButton(onClick = onUnbind, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("解绑", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LoginDialog(
    submitting: Boolean,
    onDismiss: () -> Unit,
    sendCode: suspend (String) -> String?,
    error: String?,
    onSubmit: (String, String, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var useCode by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }
    var sendMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1_000)
            cooldown--
        }
    }

    FormDialog("账号登录", onDismiss, !submitting && account.isNotBlank() && credential.isNotBlank(), { onSubmit(account.trim(), credential, useCode) }, error) {
        OutlinedTextField(account, { account = it }, label = { Text("手机号或邮箱") }, singleLine = true)
        ChoiceChips(listOf(false to "密码登录", true to "验证码登录"), useCode,
            { useCode = it; credential = ""; sendMessage = null })
        OutlinedTextField(
            credential,
            { credential = it },
            label = { Text(if (useCode) "验证码" else "密码") },
            singleLine = true,
            visualTransformation = if (useCode) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = if (useCode) {
                {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val error = sendCode(account.trim())
                                sendMessage = error
                                if (error == null) cooldown = 60
                            }
                        },
                        enabled = account.isNotBlank() && cooldown == 0
                    ) { Text(if (cooldown > 0) "${cooldown}s" else "获取验证码") }
                }
            } else null
        )
        sendMessage?.let {
            Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SetPasswordDialog(onDismiss: () -> Unit, error: String?, onSubmit: (String, String?, String?) -> Unit) {
    var password by remember { mutableStateOf("") }; var question by remember { mutableStateOf("") }; var answer by remember { mutableStateOf("") }
    FormDialog("设置备用密码", onDismiss, password.length in 4..16, { onSubmit(password, question.ifBlank { null }, answer.ifBlank { null }) }, error) {
        OutlinedTextField(password, { password = it }, label = { Text("4–16 位备用密码") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(question, { question = it }, label = { Text("密保问题（可选）") })
        OutlinedTextField(answer, { answer = it }, label = { Text("密保答案（可选）") })
    }
}

@Composable
private fun ResetPasswordDialog(onDismiss: () -> Unit, error: String?, onSubmit: (String, String, Boolean, String) -> Unit) {
    var account by remember { mutableStateOf("") }; var credential by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var useCode by remember { mutableStateOf(true) }
    FormDialog("重置备用密码", onDismiss, account.isNotBlank() && credential.isNotBlank() && password.length in 4..16, { onSubmit(account.trim(), credential.trim(), useCode, password) }, error) {
        OutlinedTextField(account, { account = it }, label = { Text("手机号或邮箱") })
        ChoiceChips(listOf(true to "验证码", false to "密保答案"), useCode, { useCode = it })
        OutlinedTextField(credential, { credential = it }, label = { Text(if (useCode) "验证码" else "密保答案") })
        OutlinedTextField(password, { password = it }, label = { Text("新备用密码") }, visualTransformation = PasswordVisualTransformation())
    }
}

@Composable
private fun FormDialog(title: String, onDismiss: () -> Unit, valid: Boolean, onConfirm: () -> Unit, error: String? = null, fields: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { fields(); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { Button(modifier = Modifier.heightIn(min = 48.dp), onClick = onConfirm, enabled = valid) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
    )
}

