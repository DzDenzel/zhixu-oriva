package com.planlock.app

import android.annotation.SuppressLint
import android.content.Intent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleIn
import com.planlock.design.Ink
import com.planlock.design.planLockBackdrop
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.planlock.design.LocalSharedScope
import com.planlock.design.LocalSharedVisibility
import com.planlock.design.LocalReducedMotion
import com.planlock.app.api.AuthSession
import com.planlock.app.api.planLockPreferences
import com.planlock.app.engine.ExecutionFeedback
import com.planlock.app.engine.FeedbackKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.planlock.app.api.ApiClient
import com.planlock.app.api.TokenStore
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.dto.DeviceRegisterRequest
import com.planlock.app.sync.AppSync
import com.planlock.app.record.RecordStore
import androidx.datastore.preferences.core.edit
import com.planlock.design.NavigationBar
import com.planlock.design.PlanLockTheme
import com.planlock.design.ThemeMode
import com.planlock.design.TopLevelDestination
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.feature.plan.PlanDetailScreen
import com.planlock.feature.plan.PlanEditScreen
import com.planlock.feature.plan.PlanScreen
import com.planlock.feature.plan.TodayScreen
import com.planlock.feature.plan.TodayLocalKey
import com.planlock.feature.plan.TodayLocalPhase
import com.planlock.feature.plan.TodayLocalStateHolder
import com.planlock.feature.settings.SettingsScreen
import com.planlock.feature.settings.SettingsDetailScreen
import com.planlock.feature.settings.SettingsDomain
import com.planlock.feature.settings.PluginSettingsScreen
import com.planlock.feature.settings.PluginDetailScreen
import com.planlock.feature.settings.PluginRepositoryScreen
import com.planlock.feature.settings.PermissionSettingsScreen
import com.planlock.feature.stats.StatsScreen
import com.planlock.plugin.PluginSourceType
import com.planlock.app.brand.BrandSplash
import com.planlock.app.brand.processSplashEntry
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val showColdSplash = processSplashEntry.claim()
        android.util.Log.i(
            "OrivaSplash",
            "activity-created instance=${System.identityHashCode(this)} cold=$showColdSplash uptimeMs=${android.os.SystemClock.uptimeMillis()}",
        )
        // BUG-103：系统启动窗口退场后、Compose 首帧前，窗口底露出的是 Theme.PlanLock 的浅色启动底。
        // 非墨序主题明暗固定，直接把窗口底换成所选主题页面底色；墨序沿用随系统明暗的原资源。
        com.planlock.design.ThemeStyleStore.current.takeIf { it != com.planlock.design.ThemeStyle.INK }?.let { style ->
            val ground = com.planlock.design.InkPalettes.of(style, style.isDark(com.planlock.design.ThemeMode.SYSTEM, false)).ground
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ground.toArgb()))
        }
        val systemSplashGone = mutableStateOf(android.os.Build.VERSION.SDK_INT < 31)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener {
                android.util.Log.i("OrivaSplash", "system-splash-exit uptimeMs=${android.os.SystemClock.uptimeMillis()}")
                it.remove()
                systemSplashGone.value = true
            }
        }
        setContent {
            AppRoot(showColdSplash, systemSplashGone.value) { dark ->
                // 手动主题可能与系统相反，系统栏图标必须使用实际主题。
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark }
                )
            }
        }
    }
}

@Composable
private fun AppRoot(showColdSplash: Boolean, systemSplashGone: Boolean, onThemeChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val preference = remember { ThemePreference(context) }
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var loaded by remember { mutableStateOf(false) }
    var needsConnectionChoice by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var choosingConnection by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var splashVisible by remember { mutableStateOf(showColdSplash) }
    var revealComplete by remember { mutableStateOf(false) }
    val todayHolder = remember { TodayLocalStateHolder.get(context) }
    val todayState by todayHolder.state.collectAsState()
    var todayKey by remember { mutableStateOf<TodayLocalKey?>(null) }
    LaunchedEffect(retry) {
        loadError = false
        try {
            val restored = withContext(Dispatchers.IO) {
                com.planlock.app.api.ConnectionStore.restore(context.planLockPreferences)
                com.planlock.plugin.PluginPublicTransport.get = com.planlock.app.api.ConnectionHttp::publicGet
                TokenStore.restore(context.planLockPreferences)
                // Preserve request history from the former settings-only location launcher.
                if (context.planLockPreferences.data.first()[com.planlock.app.api.PlanLockPreferenceKeys.locationPermissionRequested] == true) {
                    context.getSharedPreferences("capability_requests", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean(com.planlock.app.security.Capability.LOCATION.name, true).apply()
                }
                com.planlock.plugin.PluginConfigurationStore(context).registerLegacyOwner(
                    TokenStore.legacyOwnerScope(context.planLockPreferences.data.first()))
                val key = TodayLocalKey(
                    userId = TokenStore.localDataUserId,
                    scope = TokenStore.state.value.dataScope,
                    dateKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()),
                )
                key to preference.mode.first()
            }
            todayKey = restored.first
            if (showColdSplash) todayHolder.prefetch(restored.first)
            themeMode = restored.second
            needsConnectionChoice = com.planlock.app.api.ConnectionStore.needsChoice(context.planLockPreferences)
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = true }
    }
    // 系统栏图标跟随实际明暗：墨序看明暗设置，其他主题有固定明暗
    val dark = com.planlock.design.ThemeStyleStore.current.isDark(themeMode, isSystemInDarkTheme())
    SideEffect { onThemeChanged(dark) }
    // BUG-103：系统启动窗口底色跟随所选主题（API 33+，下次冷启动生效）；偏好读完后再同步，避免用默认明暗误写
    val themeStyle = com.planlock.design.ThemeStyleStore.current
    LaunchedEffect(loaded, themeStyle, themeMode) {
        if (loaded) (context as? android.app.Activity)?.let { com.planlock.app.brand.SplashThemeSync.sync(it, themeStyle, themeMode) }
    }
    PlanLockTheme(themeMode = themeMode) {
        val reducedMotion = LocalReducedMotion.current
        // 偏好读取期间（加载骨架）也铺主题底色，不露出窗口的浅色启动底（BUG-103）
        Box(Modifier.fillMaxSize().background(com.planlock.design.InkTone.ground)) {
        if (!loaded) {
            UIStatePanel(if (loadError) UiState.ERROR else UiState.LOADING,
                message = if (loadError) "本机偏好读取失败，请重试" else null,
                onAction = if (loadError) ({ retry++ }) else null)
        } else if (needsConnectionChoice) {
            androidx.activity.compose.BackHandler(enabled = connecting) { connecting = false }
            if (connecting) {
                val connection by com.planlock.app.api.ConnectionStore.state.collectAsState()
                com.planlock.feature.settings.ConnectionSettingsScreen(onBack = { connecting = false }, onLogin = { needsConnectionChoice = false }, firstRun = true)
                LaunchedEffect(connection) { if (connection.config != null) needsConnectionChoice = false }
            } else FirstConnectionScreen(choosingConnection, connectionError, onLocal = {
                if (!choosingConnection) scope.launch {
                    choosingConnection = true
                    try {
                        TokenStore.changeConnection(context.planLockPreferences, null)
                        needsConnectionChoice = false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { connectionError = "选择保存失败，请重试" }
                    finally { choosingConnection = false }
                }
            }, onConnect = { connecting = true })
        } else todayKey?.let { key ->
            val decision = startupContentDecision(showColdSplash, splashVisible, revealComplete, key, todayState)
            when (decision.stage) {
                StartupContentStage.BLOCKED -> Unit
                StartupContentStage.FULL -> AppContent(
                    themeMode = themeMode,
                    snackbar = snackbar,
                    splashVisible = splashVisible,
                    todayHolder = todayHolder,
                    todayKey = key,
                ) { mode ->
                    scope.launch {
                        try { preference.save(mode); themeMode = mode }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { snackbar.showSnackbar("主题保存失败，请重试") }
                    }
                }
            }
        }
        if (splashVisible) BrandSplash(systemSplashGone) { splashVisible = false }
        LaunchedEffect(showColdSplash, systemSplashGone, splashVisible, reducedMotion) {
            if (showColdSplash && systemSplashGone && splashVisible && !reducedMotion) {
                delay(600L)
                revealComplete = true
            }
        }
        LaunchedEffect(todayState.key, todayState.phase, todayKey) {
            if (todayState.key == todayKey && todayState.phase == TodayLocalPhase.READY) {
                android.util.Log.i(
                    "OrivaSplash",
                    "local-ready event=state-commit success=${todayState.read?.error == null} presented=false",
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@SuppressLint("InlinedApi")
@Composable
private fun AppContent(
    themeMode: ThemeMode,
    snackbar: SnackbarHostState,
    splashVisible: Boolean,
    todayHolder: TodayLocalStateHolder,
    todayKey: TodayLocalKey,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sync = remember { AppSync.get(context) }
    val commitLogged = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    SideEffect {
        if (commitLogged.compareAndSet(false, true)) {
            android.util.Log.i("OrivaSplash", "full-content-commit event=composition-commit presented=false")
        }
    }
    LaunchedEffect(splashVisible) {
        if (splashVisible) return@LaunchedEffect
        // Allow a draw after the final tree commits; plugin I/O and cold sync
        // should not compete with the brand fade or its first revealed frame.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        android.util.Log.i("OrivaSplash", "startup-effects-after-frame-boundary presented=unverified")
        // UI contributions must load even when no system service has been granted.
        // Keep signature verification and Dex I/O off the first-frame/main-thread path.
        withContext(Dispatchers.IO) {
            com.planlock.plugin.PluginHostQueries.provider = CachedPluginQueries(context)
            val configuration = com.planlock.plugin.PluginConfigurationStore(context)
            configuration.registerLegacyOwner(com.planlock.app.api.TokenStore.legacyOwnerScope(context.planLockPreferences.data.first()))
            com.planlock.plugin.PluginHostConfiguration.read = { manifest, planId ->
                configuration.values(com.planlock.app.api.TokenStore.state.value.dataScope, planId, manifest.id,
                    manifest.contributions.filterIsInstance<com.planlock.plugin.PluginContribution.FormSection>().flatMap { it.fields })
            }
            com.planlock.plugin.PluginPlatform.get(context)
        }
    }

    LaunchedEffect(splashVisible) {
        if (splashVisible) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        sync.coordinator.coldStart()
        ExecutionService.reconcile(context)
    }

    DisposableEffect(lifecycleOwner) {
        // Keep this mark in the process owner: the old Activity can be destroyed
        // while the service/process stays alive. Attachment and rotation do not check.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                sync.foregroundGate.stopped((context as? android.app.Activity)?.isChangingConfigurations == true)
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                if (sync.foregroundGate.resumed()) sync.coordinator.foregrounded()
                lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    sync.withLocalMutation { com.planlock.app.record.PlanAlarmScheduler(context).reconcile() }
                    ExecutionService.reconcile(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    val navController = rememberNavController()
    LaunchedEffect(navController) {
        val activity = context as? android.app.Activity
        val incoming = activity?.intent
        if (incoming?.getStringExtra("notification_scope") == TokenStore.state.value.dataScope) {
            val planId = incoming.getLongExtra("notification_plan_id", Long.MIN_VALUE)
            if (planId != Long.MIN_VALUE) navController.navigate("detail/$planId")
        }
        incoming?.removeExtra("notification_plan_id")
        incoming?.removeExtra("notification_scope")
    }
    val entry by navController.currentBackStackEntryAsState()
    // Keep the same real nav underlay while NavHost publishes its initial entry.
    val route = entry?.destination?.route ?: TopLevelDestination.TODAY.route
    val currentRoute by rememberUpdatedState(route)
    val topRoutes = TopLevelDestination.entries.map { it.route }.toSet()

    LaunchedEffect(Unit) {
        ExecutionFeedback.events.collect { event ->
            if (event.kind == FeedbackKind.COMPLETED) {
                lifecycleOwner.lifecycleScope.launch { snackbar.showSnackbar("计划已完成") }
            } else if (event.kind == FeedbackKind.DND_PERMISSION_MISSING) {
                lifecycleOwner.lifecycleScope.launch { snackbar.showSnackbar("免打扰未授权，自动勿扰未生效") }
            }
        }
    }
    Scaffold(
        // 背景画在根部：切页时背景静止，导航区与页面同一张纸
        modifier = Modifier.planLockBackdrop(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 透明底的 Scaffold 推不出内容色：显式给墨 / 月白，未着色的文字在深色下不再发黑
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
        snackbarHost = { com.planlock.design.InkSnackbarHost(snackbar) },
        bottomBar = {
            // 输入法弹出时收起墨条，让助理输入台贴住键盘。
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            val imeVisible = androidx.compose.foundation.layout.WindowInsets.Companion.isImeVisible
            if ((route in topRoutes || route == "assistant") && !imeVisible) {
                NavigationBar(
                    selectedRoute = route,
                    onSelect = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(TopLevelDestination.TODAY.route) { saveState = true }
                            launchSingleTop = true
                            // BUG-014：恢复 TODAY 的已保存栈会把刚弹出的计划页重新压回。
                        }
                    },
                    onAssistant = {
                        if (route != "assistant") navController.navigate("assistant") { launchSingleTop = true }
                    },
                )
            }
        }
    ) { innerPadding ->
        SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedScope provides this,
            // 页头「未登录 · 登录」胶囊点开账户页（v4.1）
            com.planlock.design.LocalSyncLoginAction provides remember(navController) {
                { navController.navigate("settings/${SettingsDomain.ACCOUNT.route}") { launchSingleTop = true } }
            }) {
        // 输入法：窗口改为 adjustResize，由这里统一让出键盘高度（扣除已占用的系统栏 / 导航条内边距），
        // 页面整体上移而不是被系统平移——知行页头与返回不再被推出屏幕。
        NavHost(navController, startDestination = TopLevelDestination.TODAY.route,
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding(),
            // v3.0「墨序」转场：同级页「落笔」（淡入 + 微升 + 微缩放），进入详情「翻页」（横移）。
            enterTransition = {
                when {
                    reducedMotion -> fadeIn(tween(0))
                    isTabRoute(initialState.destination.route) && isTabRoute(targetState.destination.route) ->
                        fadeIn(tween(260, delayMillis = 60, easing = Ink.Easing)) +
                            slideInVertically(tween(340, easing = Ink.Easing)) { it / 30 } +
                            scaleIn(tween(340, easing = Ink.Easing), initialScale = .985f)
                    else -> fadeIn(tween(240, easing = Ink.Easing)) + slideInHorizontally(tween(340, easing = Ink.Easing)) { it / 5 }
                }
            },
            exitTransition = {
                when {
                    reducedMotion -> fadeOut(tween(0))
                    isTabRoute(initialState.destination.route) && isTabRoute(targetState.destination.route) -> fadeOut(tween(140))
                    else -> fadeOut(tween(220)) + slideOutHorizontally(tween(340, easing = Ink.Easing)) { -it / 12 }
                }
            },
            popEnterTransition = {
                if (reducedMotion) fadeIn(tween(0))
                else fadeIn(tween(240, easing = Ink.Easing)) + slideInHorizontally(tween(340, easing = Ink.Easing)) { -it / 12 }
            },
            popExitTransition = {
                if (reducedMotion) fadeOut(tween(0))
                else fadeOut(tween(200)) + slideOutHorizontally(tween(340, easing = Ink.Easing)) { it / 5 }
            }
        ) {
            composable(TopLevelDestination.TODAY.route) {
                TodayScreen(
                    onCreate = { navController.navigate("edit/-1") },
                    onOpenDetail = { navController.navigate("detail/$it") },
                    localHolder = todayHolder,
                    localKey = todayKey,
                    onAsk = { navController.navigate("assistant") { launchSingleTop = true } },
                )
            }
            composable(TopLevelDestination.PLANS.route) {
                CompositionLocalProvider(LocalSharedVisibility provides this) {
                PlanScreen(
                    onCreate = { navController.navigate("edit/-1") },
                    onOpenDetail = { navController.navigate("detail/$it") }
                )
            }
            }
            composable(TopLevelDestination.STATS.route) { StatsScreen(onOpenPlan = { id -> navController.navigate("detail/$id") }) }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    themeMode = themeMode,
                    onOpenDomain = { domain -> navController.navigate("settings/${domain.route}") },
                    onOpenPermissions = { navController.navigate("settings/permissions") },
                )
            }
            SettingsDomain.entries.filterNot { it == SettingsDomain.PLUGINS || it == SettingsDomain.CONNECTION }.forEach { domain ->
                composable("settings/${domain.route}") {
                    SettingsDetailScreen(domain, themeMode, onThemeModeChange, onBack = { navController.popBackStack() })
                }
            }
            composable("settings/plugins") {
                PluginSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRepository = { source -> navController.navigate("settings/plugins/repository/${source.name}") },
                    onOpenPlugin = { id -> navController.navigate("settings/plugins/${android.net.Uri.encode(id)}") },
                )
            }
            composable("settings/connection") {
                com.planlock.feature.settings.ConnectionSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLogin = { navController.navigate("settings/${SettingsDomain.ACCOUNT.route}") },
                )
            }
            composable(
                "settings/plugins/repository/{source}",
                arguments = listOf(navArgument("source") { type = NavType.StringType }),
            ) { backStack ->
                val source = runCatching { PluginSourceType.valueOf(backStack.arguments?.getString("source").orEmpty()) }
                    .getOrDefault(PluginSourceType.OFFICIAL)
                PluginRepositoryScreen(source, onBack = { navController.popBackStack() })
            }
            composable("settings/permissions") { PermissionSettingsScreen(onBack = { navController.popBackStack() }) }
            composable("settings/plugins/{pluginId}", arguments = listOf(navArgument("pluginId") { type = NavType.StringType })) { backStack ->
                PluginDetailScreen(backStack.arguments?.getString("pluginId").orEmpty(), onBack = { navController.popBackStack() })
            }
            composable("assistant") {
                AssistantScreen(onBack = {
                    if (!navController.popBackStack()) navController.navigate(TopLevelDestination.TODAY.route) { launchSingleTop = true }
                }, onOpenPage = { destination ->
                    navController.navigate(when (destination) {
                        com.planlock.app.api.AssistantDestination.TODAY -> "today"
                        com.planlock.app.api.AssistantDestination.PLANS -> "plans"
                        com.planlock.app.api.AssistantDestination.STATS -> "stats"
                    }) { launchSingleTop = true }
                })
            }
            composable("edit/{planId}", arguments = listOf(navArgument("planId") { type = NavType.LongType })) { backStack ->
                val id = planIdForEdit(backStack.arguments?.getLong("planId"))
                PlanEditScreen(
                    planId = id,
                    onDone = {
                        lifecycleOwner.lifecycleScope.launch { snackbar.showSnackbar("保存成功") }
                        navController.navigate(TopLevelDestination.PLANS.route) {
                            popUpTo(TopLevelDestination.TODAY.route) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("detail/{planId}", arguments = listOf(navArgument("planId") { type = NavType.LongType })) { backStack ->
                val id = backStack.arguments?.getLong("planId") ?: return@composable
                CompositionLocalProvider(LocalSharedVisibility provides this) {
                    PlanDetailScreen(planId = id, onEdit = { navController.navigate("edit/$id") }, onBack = { navController.popBackStack() })
                }
            }
        }
        }
        }
    }
}

// -1 is the existing creation route; timestamp-based offline plan ids are negative too.
internal fun planIdForEdit(routeId: Long?): Long? = routeId?.takeUnless { it == -1L }

private val tabRouteSet = TopLevelDestination.entries.map { it.route }.toSet() + "assistant"

internal fun isTabRoute(route: String?): Boolean = route in tabRouteSet
