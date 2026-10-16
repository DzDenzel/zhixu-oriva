package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** UI 设计规范 v3.5：提示条按文案取语气色。 */
class InkSnackbarToneTest {
    @Test fun failuresWarn() {
        assertEquals(InkNoticeTone.WARN, inkNoticeToneOf("同步失败，已保留本机数据；可下拉重试"))
        assertEquals(InkNoticeTone.WARN, inkNoticeToneOf("免打扰未授权，自动勿扰未生效"))
    }
    @Test fun successIsDone() {
        assertEquals(InkNoticeTone.DONE, inkNoticeToneOf("保存成功"))
        assertEquals(InkNoticeTone.DONE, inkNoticeToneOf("导出完成"))
    }
    @Test fun otherwiseNeutral() = assertEquals(InkNoticeTone.NEUTRAL, inkNoticeToneOf("已复制"))
}
