package com.planlock.feature.settings

import com.planlock.design.planLockAtmosphere
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.planlock.app.api.*
import com.planlock.app.sync.AppSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @param firstRun 首启「连接我的后端」进入时为 true：页题改为「连接我的后端」，
 * 不显示当前连接 / 仅本地状态段，直接给出粘贴与扫码入口；设置里进入保持默认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(onBack: () -> Unit, onLogin: () -> Unit = {}, firstRun: Boolean = false) {
    val context = LocalContext.current
    val connection by ConnectionStore.state.collectAsState()
    val auth by TokenStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ConnectionConfig?>(null) }
    var confirmLocal by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun parse(text: String) {
        raw = ""
        preview = null
        try { preview = ConnectionConfig.decode(text); message = null }
        catch (_: IllegalArgumentException) { message = "连接配置无效，请核对版本和地址。不要导入包含密码、令牌或密钥的配置。" }
    }
    fun apply(config: ConnectionConfig?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val sameInstance = connection.instanceId == config?.instanceId
                AppSync.get(context).withSessionChange { TokenStore.changeConnection(context.planLockPreferences, config) }
                preview = null; confirmLocal = false
                message = when {
                    config == null -> "已设为仅本地使用，计划与记录已保留"
                    sameInstance -> "连接已保存，重启后仍有效"
                    else -> "连接已保存，重启后仍有效。请到账户页登录；本地数据已保留。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "连接保存失败，请重试；原连接和本地数据未改变" }
            finally { busy = false }
        }
    }

    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra("SCAN_RESULT")?.let(::parse)
    }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8_192)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            require(output.size() + count <= 8 * 1024 * 1024)
                            output.write(chunk, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("图片不可用")
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    require(options.outWidth > 0 && options.outHeight > 0)
                    var sample = 1
                    while (options.outWidth / sample > 2_048 || options.outHeight / sample > 2_048) sample *= 2
                    options.inJustDecodeBounds = false; options.inSampleSize = sample
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        ConnectionQr.decode(bitmap.width, bitmap.height, pixels)
                    } finally { bitmap.recycle() }
                }
                parse(text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "图片读取失败或没有可识别的二维码，请使用清晰的二维码图片或粘贴配置" }
            finally { busy = false }
        }
    }

    preview?.let { config ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("确认连接后端") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(config.instanceLabel)
                    Text("后端：${config.apiBaseUrl}")
                    Text("插件目录：${config.pluginsCatalogUrl}")
                    Text(if (config.lanAllowed) "允许连接此配置指定的内网地址" else "使用 HTTPS 连接")
                    Text(if (auth.dataOwnerInstance == null) {
                        "本机日程、计划和未上传改动尚未关联实例，将在首次登录并同步时归入此实例。请确认这是你要使用的后端。"
                    } else {
                        "切换后需重新登录。原实例的计划、记录和未上传改动保留在本机；连接其他实例不会上传这些已归属的数据。"
                    })
                }
            },
            confirmButton = { TextButton(onClick = { apply(config) }, enabled = !busy) { Text(if (busy) "正在保存…" else "确认连接") } },
            dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("取消") } },
        )
    }
    if (confirmLocal) AlertDialog(
        onDismissRequest = { if (!busy) confirmLocal = false }, title = { Text("仅本地使用") },
        text = { Text("保留计划、记录和未上传改动，停止后端同步并移除当前登录凭据。以后可重新连接原实例恢复同步。") },
        confirmButton = { TextButton(onClick = { apply(null) }, enabled = !busy) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirmLocal = false }, enabled = !busy) { Text("取消") } },
    )
    if (showConfig) AlertDialog(
        onDismissRequest = { showConfig = false }, title = { Text("当前公开连接配置") },
        text = { Text(connection.config?.encode().orEmpty()) },
        confirmButton = { TextButton(onClick = { showConfig = false }) { Text("关闭") } },
    )
    Scaffold(
        modifier = Modifier.planLockAtmosphere(com.planlock.design.PageAtmosphere.QUIET),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent), title = { Text(if (firstRun) "连接我的后端" else "后端连接") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!firstRun) {
                Text(connection.config?.instanceLabel ?: "仅本地使用", style = MaterialTheme.typography.titleLarge)
                Text(connection.config?.apiBaseUrl ?: "无需连接后端，也可使用本地日程与计划")
                Text("连接用于账号与同步；网络不可用时，本地功能继续可用。插件目录独立于业务地址。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!firstRun && connection.config != null && !auth.session.usable && !auth.ownershipMismatch) {
                Text("已连接 ${connection.config?.instanceLabel}，登录后开始同步")
                Button(onClick = onLogin, enabled = !busy) { Text("前往登录") }
            }
            if (auth.ownershipMismatch) Text("本机数据属于原实例或原账号，同步已暂停。请连接原实例并登录原账号恢复同步。", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(
                value = raw, onValueChange = { if (it.length <= 16_384) raw = it },
                label = { Text("粘贴连接配置") }, minLines = 3, maxLines = 5,
                supportingText = { Text("仅接受公开连接配置；输入内容隐藏，校验后显示连接预览") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy,
            )
            Button(onClick = { parse(raw) }, enabled = raw.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("校验并预览") }
            OutlinedButton(onClick = { image.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("从图片识别二维码") }
            OutlinedButton(onClick = {
                try { scan.launch(Intent("com.google.zxing.client.android.SCAN").putExtra("SCAN_MODE", "QR_CODE_MODE")) }
                catch (_: android.content.ActivityNotFoundException) { message = "此设备没有扫码应用，可从图片识别二维码或粘贴连接配置" }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("使用系统扫码") }
            if (!firstRun && connection.config != null) {
                TextButton(onClick = { showConfig = true }, enabled = !busy) { Text("查看公开连接配置") }
                TextButton(onClick = { confirmLocal = true }, enabled = !busy) { Text("仅本地使用") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
