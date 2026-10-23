package com.planlock.app.api

/** Stable keys belong in plans; names are resolved against the current account's catalog. */
data class PlanTypeDefinition(val key: String, val name: String, val color: String, val deleted: Boolean = false)

object PlanTypes {
    val colors = setOf("study", "work", "sport", "sleep", "rose", "amber", "hobby", "other")
    val presets = listOf(
        PlanTypeDefinition("学习", "学习", "study"), PlanTypeDefinition("工作", "工作", "work"),
        PlanTypeDefinition("运动", "运动", "sport"), PlanTypeDefinition("阅读", "阅读", "amber"),
        PlanTypeDefinition("休息", "休息", "sleep"), PlanTypeDefinition("生活", "生活", "rose"),
        PlanTypeDefinition("兴趣", "兴趣", "hobby"), PlanTypeDefinition("其他", "其他", "other"),
    )
    private val aliases = mapOf("study" to "学习", "work" to "工作", "sport" to "运动", "sleep" to "休息", "睡眠" to "休息",
        "reading" to "阅读", "rest" to "休息", "life" to "生活", "hobby" to "兴趣", "other" to "其他")
    fun definition(value: String?, custom: List<PlanTypeDefinition> = emptyList()): PlanTypeDefinition =
        presets.firstOrNull { it.key == (aliases[value] ?: value) }
            ?: custom.firstOrNull { it.key == value && !it.deleted }
            ?: presets.last()
    fun label(value: String?, custom: List<PlanTypeDefinition> = emptyList()): String = definition(value, custom).name
    fun validateName(input: String): String = input.trim().also {
        require(it.isNotEmpty() && it.codePointCount(0, it.length) <= 6 && it.none(Char::isISOControl)) { "类型名称须为1至6字" }
    }
}
