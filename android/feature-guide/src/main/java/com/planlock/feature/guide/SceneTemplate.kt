package com.planlock.feature.guide

/** v2 场景模板只预填提醒、勿扰、通知与“不计入分心”类别。 */
data class SceneTemplate(
    val type: String,
    val remindAdvanceMin: Int,
    val autoDnd: Boolean,
    val notifySilence: Boolean,
    val excludedCategories: List<String>,
)

object SceneTemplates {
    val STUDY = SceneTemplate("学习", 15, true, true, listOf("词典", "笔记", "浏览器", "音乐"))
    val SPORT = SceneTemplate("运动", 15, false, true, listOf("音乐", "运动健康", "电话"))
    val SLEEP = SceneTemplate("睡眠", 15, true, true, emptyList())
    val WORK = SceneTemplate("工作", 15, true, true, emptyList())
    val CUSTOM = SceneTemplate("自定义", 15, true, true, emptyList())

    fun byType(type: String): SceneTemplate? = when (type) {
        "学习" -> STUDY
        "运动" -> SPORT
        "睡眠" -> SLEEP
        "工作" -> WORK
        "自定义" -> CUSTOM
        else -> null
    }
}
