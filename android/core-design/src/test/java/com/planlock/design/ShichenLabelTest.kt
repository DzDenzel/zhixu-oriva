package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** UI 设计规范 v3.4：日晷中心的十二时辰时刻名。 */
class ShichenLabelTest {
    @Test fun midnightBelongsToZiZheng() = assertEquals("子正", shichenLabel(0))
    @Test fun lateEveningStartsZiChu() = assertEquals("子初一刻", shichenLabel(23 * 60 + 20))
    @Test fun earlyMorningCountsKe() = assertEquals("丑正三刻", shichenLabel(2 * 60 + 59))
    @Test fun afternoonIsWeiChu() = assertEquals("未初二刻", shichenLabel(13 * 60 + 30))
    @Test fun noonIsWuZheng() = assertEquals("午正", shichenLabel(12 * 60))
    @Test fun wrapsOutOfRangeMinutes() = assertEquals("子正", shichenLabel(1440))
}
