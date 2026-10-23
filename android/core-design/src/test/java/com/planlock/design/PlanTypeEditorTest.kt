package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanTypeEditorTest {
    private val existing = listOf("学习", "工作", "练琴")

    @Test fun acceptsUpToSixCharacters() {
        assertNull(planTypeNameIssue("家务", existing))
        assertNull(planTypeNameIssue("  晨跑打卡  ", existing))
        assertNull(planTypeNameIssue("一二三四五六", existing))
    }

    @Test fun rejectsEmptyLongAndDuplicate() {
        assertEquals("请填写类型名称", planTypeNameIssue("   ", existing))
        assertEquals("名称最多 6 个字", planTypeNameIssue("一二三四五六七", existing))
        assertEquals("已有同名类型", planTypeNameIssue(" 练琴", existing))
    }

    @Test fun countsSurrogatePairsAsOneCharacter() {
        assertNull(planTypeNameIssue("🎹练琴", existing))
    }

    @Test fun deleteDetailMentionsPlansMovingToOther() {
        assertEquals("已有 3 个计划用了这个类型，删除后会归入「其他」。", planTypeDeleteDetail(3))
        assertEquals("还没有计划用这个类型。", planTypeDeleteDetail(0))
    }
}
