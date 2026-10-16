package com.planlock.app.brand

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.planlock.app.R
import com.planlock.design.ThemeMode
import com.planlock.design.ThemeStyle

/**
 * BUG-103（任务包 §3.3 方案丙）：API 33+ 用 `SplashScreen.setSplashScreenTheme` 让系统启动窗口底色跟随所选主题。
 * 系统持久记住该设置，下次冷启动生效；不改配置、不重建 Activity，热切换不受影响。
 * API 31–32 及以下维持现状（启动底色只随系统明暗），列为已知限制。
 */
internal object SplashThemeSync {
    private const val FILE = "oriva_ui"
    private const val KEY = "splash_theme_applied"

    /** 当前主题对应的启动样式键；墨序跟随系统 = 「ink-system」（不设启动主题）。 */
    fun keyFor(style: ThemeStyle, mode: ThemeMode): String =
        if (style == ThemeStyle.INK) "ink-${mode.value}" else style.key

    /** 启动样式；null = 沿用 Theme.PlanLock（随系统明暗）。 */
    fun styleFor(style: ThemeStyle, mode: ThemeMode): Int? = when (style) {
        ThemeStyle.INK -> when (mode) {
            ThemeMode.SYSTEM -> null
            ThemeMode.LIGHT -> R.style.Theme_PlanLock_Splash_InkLight
            ThemeMode.DARK -> R.style.Theme_PlanLock_Splash_InkDark
        }
        ThemeStyle.CELADON -> R.style.Theme_PlanLock_Splash_Celadon
        ThemeStyle.DUSK -> R.style.Theme_PlanLock_Splash_Dusk
        ThemeStyle.BAUHAUS -> R.style.Theme_PlanLock_Splash_Bauhaus
        ThemeStyle.ATLAS -> R.style.Theme_PlanLock_Splash_Atlas
        ThemeStyle.PHOSPHOR -> R.style.Theme_PlanLock_Splash_Phosphor
    }

    /**
     * 选择主题 / 明暗后调用；启动时也调用一次，已保存的设置与上次写给系统的不一致时补设（如升级安装后首次启动）。
     * 首次安装、从未选择过主题时 = 墨序跟随系统，不设启动主题，沿用现有启动资源。
     */
    fun sync(activity: Activity, style: ThemeStyle, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val key = keyFor(style, mode)
        val prefs = activity.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, null) == key) return
        runCatching { activity.splashScreen.setSplashScreenTheme(styleFor(style, mode) ?: Resources.ID_NULL) }
            .onSuccess { prefs.edit().putString(KEY, key).apply() }
            .onFailure { android.util.Log.w("OrivaSplash", "splash-theme-sync failed key=$key", it) }
    }
}
