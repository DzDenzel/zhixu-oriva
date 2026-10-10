package com.planlock.app.api

/**
 * ADR-006 L4：仅包名精确命中，不按厂商前缀或 FLAG_SYSTEM 整体放行。
 * AOSP 来源：各基础应用 AndroidManifest.xml（android.googlesource.com/platform/packages/apps/）。
 * Google 变体：Medium_Phone 已安装包列表（本轮测试报告）。核心角色优先于此开关。
 */
val DEFAULT_BASIC_TOOLS: Set<String> = setOf(
    "com.android.dialer", "com.android.mms", "com.android.contacts",
    "com.android.camera", "com.android.camera2",
    "com.android.gallery3d", "com.android.calculator2", "com.android.documentsui",
    "com.android.fileexplorer", "com.android.weather",
    "com.google.android.dialer", "com.google.android.apps.messaging", "com.google.android.contacts",
    "com.google.android.GoogleCamera", "com.google.android.apps.photos",
    "com.google.android.calculator", "com.google.android.documentsui"
)

/**
 * ColorOS 变体参考：Trinity College Android 隐私测量原始包清单（neversleepingears 附录）
 * https://www.scss.tcd.ie/Doug.Leith/pubs/additional_material_neversleepingears.pdf
 * 仅作为设备兼容清单；当前一加版本是否安装及具体基础功能可用性仍须真机终验。
 */
val COLOROS_BASIC_TOOLS: Set<String> = setOf(
    "com.oppo.camera", "com.coloros.gallery3d",
    "com.coloros.calculator", "com.coloros.filemanager", "com.coloros.weather2"
)
