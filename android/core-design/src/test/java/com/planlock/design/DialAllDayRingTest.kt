package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Test

class DialAllDayRingTest {
    /** BUG-102 任务包 §3.2 定案值。 */
    @Test fun ringParametersMatchSpec() {
        assertEquals(2f, DialAllDayRing.WIDTH_DP, 0f)
        assertEquals(.35f, DialAllDayRing.ALPHA, 0f)
    }
}
