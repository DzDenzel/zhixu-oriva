package com.planlock.app

import android.annotation.SuppressLint
import android.content.Intent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.planlock.app.api.planLockPreferences
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
import com.planlock.feature.plan.TodayFirstFrame
import com.planlock.feature.plan.TodayLocalKey
import com.planlock.feature.plan.TodayLocalPhase
import com.planlock.feature.plan.TodayLocalRead
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
        val systemSplashGone = mutableStateOf(android.os.Build.VERSION.SDK_INT < 31)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener {
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
                TokenStore.restore(context.planLockPreferences)
                val key = TodayLocalKey(
                    userId = TokenStore.userId,
                    dateKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()),
                )
                key to preference.mode.first()
            }
            todayKey = restored.first
            if (showColdSplash) todayHolder.prefetch(restored.first)
            themeMode = restored.second
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = true }
    }
    val dark = themeMode.isDark(isSystemInDarkTheme())
    SideEffect { onThemeChanged(dark) }
    PlanLockTheme(themeMode = themeMode) {
        val reducedMotion = LocalReducedMotion.current
        Box(Modifier.fillMaxSize()) {
        if (!loaded) {
            UIStatePanel(if (loadError) UiState.ERROR else UiState.LOADING,
                message = if (loadError) "本机偏好读取失败，请重试" else null,
                onAction = if (loadError) ({ retry++ }) else null)
        } else todayKey?.let { key ->
            val decision = startupContentDecision(showColdSplash, splashVisible, revealComplete, key, todayState)
            when (decision.stage) {
                StartupContentStage.BLOCKED -> Unit
                StartupContentStage.FIRST_FRAME,
                StartupContentStage.FULL -> AppContent(
                    themeMode = themeMode,
                    snackbar = snackbar,
                    splashVisible = splashVisible,
                    todayHolder = todayHolder,
                    todayKey = key,
                    startupStage = decision.stage,
                    startupRead = decision.read,
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

@Composable
private fun TodayStartupShell(read: TodayLocalRead) {
    Scaffold(
        bottomBar = {
            NavigationBar(
                selectedRoute = TopLevelDestination.TODAY.route,
                onSelect = {},
                onAssistant = {},
            )
        },
    ) { innerPadding ->
        TodayFirstFrame(read, Modifier.fillMaxSize().padding(innerPadding))
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
    startupStage: StartupContentStage,
    startupRead: TodayLocalRead?,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    if (startupStage == StartupContentStage.FIRST_FRAME) {
        val admissionLogged = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        if (admissionLogged.compareAndSet(false, true)) {
            android.util.Log.i("OrivaSplash", "admission-open stage=first-frame event=composition-entry presented=false")
        }
        TodayStartupShell(requireNotNull(startupRead))
        return
    }
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
    LaunchedEffect(Unit) {
        // UI contributions must load even when no system service has been granted.
        // Keep signature verification and Dex I/O off the first-frame/main-thread path.
        withContext(Dispatchers.IO) {
            com.planlock.plugin.PluginHostQueries.provider = CachedPluginQueries(context)
            val configuration = com.planlock.plugin.PluginConfigurationStore(context)
            com.planlock.plugin.PluginHostConfiguration.read = { manifest, planId ->
                configuration.values(com.planlock.app.api.TokenStore.localDataUserId, planId, manifest.id,
                    manifest.contributions.filterIsInstance<com.planlock.plugin.PluginContribution.FormSection>().flatMap { it.fields })
            }
            com.planlock.plugin.PluginPlatform.get(context)
        }
    }

    LaunchedEffect(Unit) {
        sync.coordinator.coldStart()
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ExecutionService::class.java)) }
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
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    val navController = rememberNavController()
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
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route in topRoutes || route == "assistant") {
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
        CompositionLocalProvider(LocalSharedScope provides this) {
        NavHost(navController, startDestination = TopLevelDestination.TODAY.route, modifier = Modifier.fillMaxSize().padding(innerPadding),
            enterTransition = { fadeIn(tween(if (reducedMotion) 0 else 180)) },
            exitTransition = { fadeOut(tween(if (reducedMotion) 0 else 180)) },
            popEnterTransition = { fadeIn(tween(if (reducedMotion) 0 else 180)) },
            popExitTransition = { fadeOut(tween(if (reducedMotion) 0 else 180)) }
        ) {
            composable(TopLevelDestination.TODAY.route) {
                TodayScreen(
                    onCreate = { navController.navigate("edit/-1") },
                    onOpenDetail = { navController.navigate("detail/$it") },
                    localHolder = todayHolder,
                    localKey = todayKey,
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
            composable(TopLevelDestination.STATS.route) { StatsScreen() }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    themeMode = themeMode,
                    onOpenDomain = { domain -> navController.navigate("settings/${domain.route}") },
                    onOpenPermissions = { navController.navigate("settings/permissions") },
                )
            }
            SettingsDomain.entries.filterNot { it == SettingsDomain.PLUGINS }.forEach { domain ->
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
                AssistantScreen(onOpenPage = { destination ->
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
