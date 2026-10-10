package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Test

class UiStateCopyTest {
    @Test
    fun `empty schedule state names schedule in title and action`() {
        val copy = UiStateCopy.resolve(UiState.EMPTY, emptySubject = "日程")

        assertEquals("还没有日程", copy.title)
        assertEquals("创建第一个日程", copy.actionLabel)
    }
}
