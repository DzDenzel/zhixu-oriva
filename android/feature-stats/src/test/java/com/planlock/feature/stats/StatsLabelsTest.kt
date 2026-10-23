package com.planlock.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsLabelsTest {
    @Test fun earlyEndingReadsAsDateTimeAndMethod() {
        assertEquals("10 月 13 日 18:02  主动结束", earlyEndingLine("2026-10-13T18:02:20", "user_ended", 2026))
        assertEquals("10 月 13 日 08:05  输入备用密码后结束", earlyEndingLine("2026-10-13 08:05:00", "backup_password", 2026))
        assertEquals("2025 年 12 月 31 日 23:59  主动结束", earlyEndingLine("2025-12-31T23:59:59", null, 2026))
    }

    @Test fun unknownValuesNeverLeakRawCodes() {
        assertEquals("时间未知  主动结束", earlyEndingLine(null, "", 2026))
        assertEquals("时间未知  提前结束", earlyEndingLine("昨天", "some_new_code", 2026))
    }

    @Test fun zonedTimestampIsConvertedNotPrintedRaw() {
        val line = earlyEndingLine("2026-10-13T10:02:20Z", "user_ended", 2026)
        assertTrue(line, !line.contains("T") && !line.contains("Z"))
        assertTrue(line, line.endsWith("主动结束"))
    }

    @Test fun planDurationIsReadable() {
        assertEquals("0 分钟", planDurationLabel(0))
        assertEquals("45 分钟", planDurationLabel(45 * 60_000L))
        assertEquals("2 小时", planDurationLabel(120 * 60_000L))
        assertEquals("2 小时 5 分", planDurationLabel(125 * 60_000L))
        assertEquals("约 168 小时", planDurationLabel((167 * 60 + 53) * 60_000L))
        assertEquals("0 分钟", planDurationLabel(-5))
    }
}
