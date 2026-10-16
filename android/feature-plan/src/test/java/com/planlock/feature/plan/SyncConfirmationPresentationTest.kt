package com.planlock.feature.plan

import com.planlock.app.api.dto.SyncPlanView
import com.planlock.app.api.dto.SyncScheduleView
import com.planlock.app.api.dto.SyncStrategyView
import com.planlock.app.record.SyncConfirmation
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConfirmationPresentationTest {
    private val plan = PlanSnapshotEntity(7, 1, "我的阅读", "学习", "23:00:00", "01:00:00",
        "{\"type\":\"weekly\",\"weekdays\":[2,4]}", null, null, 10, null, 1, 3)

    @Test
    fun `plan comparison preserves different titles times recurrence and execution settings`() {
        val confirmation = SyncConfirmation("plan", 7, localPlan = plan,
            localStrategy = StrategySnapshotEntity(70, 7, "[\"游戏\"]", null, true, false, 3),
            remotePlan = SyncPlanView(id = 7, name = "对方阅读", startTime = "20:00:00", endTime = "21:00:00",
                repeatRule = "{\"type\":\"daily\"}", status = 0),
            remoteStrategy = SyncStrategyView(id = 70, planId = 7, autoDnd = false, notifySilence = true))

        val result = confirmation.presentation()

        assertEquals("我的阅读", result.title)
        assertEquals("我的阅读" to "对方阅读", result.field("标题"))
        assertEquals("每周 周二、周四" to "每天", result.field("日期 / 重复"))
        assertEquals("23:00 至 01:00（跨天）" to "20:00 至 21:00", result.field("时间"))
        assertEquals("已启用" to "已暂停", result.field("执行状态"))
        assertEquals("开启" to "关闭", result.field("免打扰"))
        assertEquals("关闭" to "开启", result.field("通知静默"))
        assertEquals("游戏" to "未设置", result.field("限制应用"))
    }

    @Test
    fun `deleted local item still has title and remote evidence for confirmation entry`() {
        val confirmation = SyncConfirmation("plan", 7, deleting = true, localPlan = plan,
            remotePlan = SyncPlanView(id = 7, name = "对方的新标题"))
        val result = confirmation.presentation()

        assertEquals("我的阅读", result.title)
        assertEquals("对方修改过，仍要删除吗？", result.explanation)
        assertEquals("本机已删除\n删除前：我的阅读" to "对方的新标题", result.field("标题"))
        assertEquals("保留对方版本", result.keepRemoteLabel)
        assertEquals("保留我的版本", result.keepMineLabel)
        assertEquals(7L, confirmation.affectedPlanId())
    }

    @Test
    fun `remote deletion explains restoration instead of showing an empty version`() {
        val result = SyncConfirmation("plan", 7, localPlan = plan).presentation()

        assertEquals("接受删除", result.keepRemoteLabel)
        assertEquals("恢复我的版本", result.keepMineLabel)
        assertEquals("我的阅读" to "对方已删除", result.field("标题"))
        assertTrue(result.explanation.contains("恢复"))
    }

    @Test
    fun `delete intent without local snapshot remains reachable by remote title`() {
        val result = SyncConfirmation("plan", 7, deleting = true,
            remotePlan = SyncPlanView(id = 7, name = "对方仍在编辑的计划")).presentation()

        assertEquals("对方仍在编辑的计划", result.title)
        assertEquals("本机已删除" to "对方仍在编辑的计划", result.field("标题"))
    }

    @Test
    fun `all day schedule uses dates instead of stale hidden clock values`() {
        val schedule = ScheduleSnapshotEntity(12, 1, "我的旅行", "2026-10-13", "09:00:00", "2026-10-14", "18:00:00",
            true, "上海", null, null, null, null, false, "Asia/Shanghai", 1, 3)
        val result = SyncConfirmation("schedule", 12, localSchedule = schedule,
            remoteSchedule = SyncScheduleView(id = 12, title = "对方旅行", startDate = "2026-10-15", endDate = "2026-10-15",
                startTime = "10:00:00", endTime = "11:00:00", allDay = false)).presentation()

        assertEquals("2026-10-13 至 2026-10-14" to "2026-10-15", result.field("日期"))
        assertEquals("全天" to "10:00 至 11:00", result.field("时间"))
        assertEquals("上海" to "未设置", result.field("地点"))
    }

    @Test
    fun `plan strategy conflict remains a single owning plan confirmation`() {
        val confirmation = SyncConfirmation("plan", 7,
            localPlan = plan,
            localStrategy = StrategySnapshotEntity(70, 7, null, null, true, null, 3),
            remotePlan = SyncPlanView(id = 7, name = "对方阅读"),
            remoteStrategy = SyncStrategyView(id = 70, planId = 7, autoDnd = false))

        assertEquals(7L, confirmation.affectedPlanId())
        assertEquals("我的阅读", confirmation.presentation().title)
        assertEquals("开启" to "关闭", confirmation.presentation().field("免打扰"))
    }

    private fun ConfirmationPresentation.field(label: String): Pair<String, String> =
        fields.single { it.label == label }.let { it.mine to it.remote }
}
