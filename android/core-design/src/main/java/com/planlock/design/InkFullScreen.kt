package com.planlock.design

import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 全屏编辑页（v4.1）：在任何页面之上铺开一整页，与「新建计划」同一种进入方式——
 * 底色 + 当前主题背景、透明系统栏、图标深浅随主题。调用方负责页内 insets（systemBars / ime）。
 * 返回键与系统返回手势走 [onDismissRequest]；保存中可由调用方忽略。
 */
@Composable
fun InkFullScreenPage(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val light = !LocalInkPalette.current.isDark
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0f)
            window.setWindowAnimations(android.R.style.Animation_Activity)
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
            } else {
                @Suppress("DEPRECATION")
                run {
                    val decor = window.decorView
                    var flags = decor.systemUiVisibility
                    val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
                    flags = if (light) flags or lightFlags else flags and lightFlags.inv()
                    decor.systemUiVisibility = flags
                }
            }
        }
        Box(Modifier.fillMaxSize().background(InkTone.ground).planLockBackdrop()) { content() }
    }
}
