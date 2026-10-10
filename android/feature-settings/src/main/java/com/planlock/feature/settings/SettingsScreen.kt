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

    suspend fun loadDevices() {
        loadingDevices = true
        try {
            devices = sync.cache.read(TokenStore.userId).devices.orEmpty()
            deviceReadError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { deviceReadError = "本机设备快照读取失败，可重试同步" }
        loadingDevices = false
    }
    LaunchedEffect(syncState.revision) {
        loadDevices()
        weatherLocation = weatherStore.location()
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
                                    val merged = sync.withSessionChange {
                                        if (!loginAttempts.isCurrent(attempt)) throw CancellationException("登录操作已取消")
                                        TokenStore.save(context.planLockPreferences,
                                            AuthSession(auth.token, auth.userId, auth.deviceId, auth.guestUserId, true))
                                        try { auth.guestUserId?.takeIf { TokenStore.canSync }?.let { api.merge(MergeRequest(it)).data?.get("mergedPlans") } }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { null }
                                    }
                                    feedback(if (merged != null) "登录成功，已合并 $merged 个本机计划" else "登录成功")
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
                        if (ok) feedback("备用密码已重置") else dialogError = "重置失败，请检查验证码或密保答案"
                        if (ok) dialog = null
                    }
                }
            )
            Dialog.WEATHER_CITY -> WeatherCityDialog(
                current = weatherLocation,
                search = weatherClient::searchCities,
                onDismiss = { dialog = null },
                onSelect = { location ->
                    scope.launch {
                        weatherStore.saveLocation(location)
                        weatherLocation = location
                        feedback("天气城市已设为 ${location.displayName}")
                        dialog = null
                    }
                },
                onClear = {
                    scope.launch {
                        weatherStore.clearLocation()
                        weatherLocation = null
                        feedback("天气城市已清除")
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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(domain.title) },
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
            when (domain) {
                SettingsDomain.THEME -> item {
                    SettingsCard {
                        Text("主题模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        ChoiceChips(ThemeMode.entries.map { it to it.label }, themeMode, onThemeModeChange)
                    }
                }
                SettingsDomain.BASIC_TOOLS -> item {
                    SettingsCard {
                        SettingSwitch("基础工具默认不计入分心", "保留常用工具；系统必要功能始终不计入分心", basicToolsEnabled) { enabled ->
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
                    SettingsCard {
                        CompactInfoRow("天气城市", weatherLocation?.displayName ?: "未设置时今日页不显示天气")
                        Button(onClick = { dialog = Dialog.WEATHER_CITY }) { Text(if (weatherLocation == null) "设置城市" else "更改城市") }
                    }
                }
                SettingsDomain.ACCOUNT -> item {
                    StatusCard(accountUi.title, accountUi.description) {
                        StatusActionRow(
                            primaryLabel = accountUi.loginLabel.takeIf { it.isNotEmpty() },
                            onPrimary = { dialog = Dialog.LOGIN },
                            secondaryLabel = if (accountUi.showSignOut) "退出登录" else null,
                            onSecondary = {
                            loginAttempts.invalidate()
                            loginJob?.cancel()
                            scope.launch {
                                try {
                                    sync.withSessionChange { TokenStore.signOut(context.planLockPreferences) }
                                    feedback("已退出登录，本地数据已保留")
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { feedback("退出登录保存失败，请重试") }
                            }
                            },
                        )
                    }
                }
                SettingsDomain.PASSWORD -> item {
                    SettingsCard {
                        Text("用于敏感操作确认；执行期确认默认关闭。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingSwitch("执行期动作确认", "开启后，执行期间暂停、编辑或删除计划需要备用密码", actionGateEnabled) { enabled ->
                            scope.launch {
                                runCatching { context.planLockPreferences.edit { it[PlanLockPreferenceKeys.actionGateEnabled] = enabled } }
                                    .onFailure { feedback("动作确认设置保存失败，请重试") }
                            }
                        }
                        StatusActionRow(primaryLabel = "设置密码", onPrimary = { dialog = Dialog.SET_PASSWORD },
                            secondaryLabel = "重置密码", onSecondary = { dialog = Dialog.RESET_PASSWORD })
                    }
                }
                SettingsDomain.DEVICES -> {
                    item {
                        SettingsCard {
                            GroupHeader("已关联设备", if (loadingDevices) "读取中" else "${devices.size} 台")
                            when {
                                loadingDevices -> Text("正在读取设备…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                devices.isEmpty() -> CompactInfoRow("暂无可管理设备", "登录并联网后可查看和管理设备")
                            }
                        }
                    }
                    if (!loadingDevices) items(devices, key = { it.id }) { device ->
                        DeviceRow(
                            device,
                            onPrimary = { scope.launch { runCatching { api.setPrimaryDevice(device.id) }; sync.coordinator.refresh()?.join(); loadDevices() } },
                            onUnbind = { scope.launch { runCatching { api.unbindDevice(device.id) }; sync.coordinator.refresh()?.join(); loadDevices() } },
                        )
                    }
                }
                SettingsDomain.PLUGINS -> Unit
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
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(status, style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    PresentationCard(content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(device: DeviceDto, onPrimary: () -> Unit, onUnbind: () -> Unit) {
    SettingsCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(device.deviceName ?: "未命名设备", style = MaterialTheme.typography.titleMedium); Text(devicePlatformLabel(device.platform), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if (device.online == true) "在线" else "离线", color = if (device.online == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (device.isPrimary == true) Text("主设备", color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (device.isPrimary != true) OutlinedButton(onClick = onPrimary, modifier = Modifier.heightIn(min = 48.dp)) { Text("设为主设备") }
            OutlinedButton(onClick = onUnbind, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("解绑") }
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
private fun WeatherCityDialog(
    current: WeatherLocation?,
    search: suspend (String) -> List<WeatherLocation>,
    onDismiss: () -> Unit,
    onSelect: (WeatherLocation) -> Unit,
    onClear: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherLocation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置天气城市") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                current?.let { Text("当前：${it.displayName}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(query, { query = it }, label = { Text("输入城市名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            searched = true
                            results = try {
                                search(query)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                            loading = false
                        }
                    },
                    enabled = query.isNotBlank() && !loading,
                ) { Text(if (loading) "搜索中…" else "搜索") }
                if (searched && !loading && results.isEmpty()) {
                    Text("没有找到匹配城市", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                results.forEach { location ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(location) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(location.displayName, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (current != null) TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除城市") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } },
    )
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

