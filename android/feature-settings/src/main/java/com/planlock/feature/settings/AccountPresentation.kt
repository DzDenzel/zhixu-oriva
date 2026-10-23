package com.planlock.feature.settings

import com.planlock.app.api.AuthState

internal data class AccountPresentation(val title: String, val description: String, val loginLabel: String, val showSignOut: Boolean)

internal fun accountPresentation(state: AuthState): AccountPresentation = when {
    state.signedOut -> AccountPresentation("已退出登录", "当前显示未登录内容；账号数据已保留，登录后恢复", "登录", false)
    state.session.signedIn && state.expired -> AccountPresentation("登录已过期", "本地数据仍可使用，请重新登录恢复同步", "重新登录", true)
    state.session.signedIn -> AccountPresentation("已登录账号", "计划可与其他设备同步", "", true)
    else -> AccountPresentation("本机游客模式", "登录后可合并本机计划与日程", "登录", false)
}
