package com.planlock.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * 名称/分类 → 已安装应用包名 解析层（BUG-008）。
 * 规则：① 含 "." 视为包名精确匹配 ② 本地应用库映射 ③ 系统「游戏」分类兜底。
 */
class AppPackageResolver(private val context: Context) {

    data class InstalledApp(
        val name: String,
        val packageName: String,
        val category: String,
        val icon: Drawable?
    )

    private val categoryPackages: Map<String, List<String>> = mapOf(
        "游戏" to listOf("com.tencent.tmgp.sgame", "com.miHoYo.Yuanshen", "com.tencent.tmgp.pubgmhd"),
        "短视频" to listOf("com.ss.android.ugc.aweme", "com.smile.gifmaker"),
        "社交" to listOf("com.tencent.mobileqq", "com.tencent.mm"),
        "影音" to listOf("tv.danmaku.bili", "com.qiyi.video", "com.tencent.qqlive"),
        "资讯" to listOf("com.ss.android.article.news", "com.netease.newsreader.activity", "com.sina.news"),
        "购物" to listOf("com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.pinduoduo", "com.achievo.vipshop"),
        "学习" to listOf("com.youdao.dict", "com.chaoxing.mobile", "com.baidu.homework"),
        "工具" to listOf("com.android.calculator2", "com.android.fileexplorer", "com.android.settings"),
        "音乐" to listOf("com.netease.cloudmusic", "com.tencent.qqmusic"),
        "运动健康" to listOf("com.huawei.health", "com.xiaomi.hm.health"),
        "电话" to listOf("com.android.dialer"),
        "短信" to listOf("com.android.mms"),
        "地图" to listOf("com.autonavi.minimap", "com.baidu.BaiduMap"),
        "相机" to listOf("com.android.camera"),
        "词典" to listOf("com.youdao.dict"),
        "笔记" to listOf("com.example.notepad"),
        "浏览器" to listOf("com.android.browser", "com.tencent.mtt")
    )

    private val pm: PackageManager get() = context.packageManager

    /** 把黑/白名单名称解析为已安装包名集合。 */
    fun resolve(names: List<String>): Set<String> {
        val installed = installedPackages()
        val games = installedGames()
        val result = mutableSetOf<String>()
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            if (name.contains(".")) {
                if (installed.contains(name)) result.add(name)
            } else {
                categoryPackages[name]?.filter { installed.contains(it) }?.let { result.addAll(it) }
                if (name == "游戏" || name == "娱乐") {
                    result.addAll(games)
                }
            }
        }
        return result
    }

    /** 返回具有桌面入口且当前系统允许查询的应用，供计划选择器展示。 */
    fun installedApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val app = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                val packageName = app.packageName ?: return@mapNotNull null
                InstalledApp(
                    name = pm.getApplicationLabel(app).toString(),
                    packageName = packageName,
                    category = categoryOf(app, packageName),
                    icon = runCatching { pm.getApplicationIcon(app) }.getOrNull()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }

    private fun categoryOf(app: ApplicationInfo, packageName: String): String {
        categoryPackages.entries.firstOrNull { packageName in it.value }?.key?.let { known ->
            return when (known) {
                "音乐" -> "影音"
                "运动健康", "词典", "笔记" -> "学习"
                else -> known
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return when (app.category) {
                ApplicationInfo.CATEGORY_GAME -> "游戏"
                ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> "影音"
                ApplicationInfo.CATEGORY_SOCIAL -> "社交"
                ApplicationInfo.CATEGORY_NEWS -> "资讯"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "学习"
                else -> "工具"
            }
        }
        return "工具"
    }

    private fun installedPackages(): Set<String> =
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .mapNotNull { it.packageName }
            .toSet()

    private fun installedGames(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptySet()
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.category == ApplicationInfo.CATEGORY_GAME }
            .mapNotNull { it.packageName }
            .toSet()
    }
}
