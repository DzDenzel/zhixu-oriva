package com.planlock.feature.settings

import com.planlock.app.api.AuthState
import com.planlock.design.InkHue

/** 每个设置栏目的印章（字 + 类别色）：总览行与子页题签共用，保证两处一致。 */
internal data class SettingsMark(val glyph: String, val hue: InkHue.Hue)

internal fun settingsMark(domain: SettingsDomain): SettingsMark = when (domain) {
    SettingsDomain.THEME -> SettingsMark("色", InkHue.rose)
    SettingsDomain.BASIC_TOOLS -> SettingsMark("具", InkHue.work)
    SettingsDomain.PLUGINS -> SettingsMark("插", InkHue.sleep)
    SettingsDomain.WEATHER -> SettingsMark("天", InkHue.study)
    SettingsDomain.ACCOUNT -> SettingsMark("户", InkHue.sport)
    SettingsDomain.CONNECTION -> SettingsMark("连", InkHue.work)
    SettingsDomain.DEVICES -> SettingsMark("机", InkHue.amber)
    SettingsDomain.PASSWORD -> SettingsMark("钥", InkHue.seal)
}

internal val permissionsMark = SettingsMark("权", InkHue.study)

internal fun settingsMark(item: SettingsOverviewItem): SettingsMark = item.domain?.let(::settingsMark) ?: permissionsMark

/**
 * 设备栏目只在「已登录且未退出」时展示与管理设备；总览计数与子页列表都用这一条判断（v4.1，修数据不同步）。
 */
internal fun settingsDevicesManageable(state: AuthState): Boolean = state.session.signedIn && !state.signedOut
