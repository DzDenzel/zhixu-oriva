package com.planlock.plugin.forceblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViolationEventGateTest {
    @Test
    fun repeatedWindowEventsForOneLaunchRunOnePluginAction() {
        val gate = ViolationEventGate(windowMillis = 3_000)
        assertTrue(gate.shouldRecord(5, "video", 10_000))
        assertFalse(gate.shouldRecord(5, "video", 11_500))
        assertTrue(gate.shouldRecord(5, "video", 13_000))
    }

    @Test
    fun anotherPlanOrPackageIsANewAction() {
        val gate = ViolationEventGate(windowMillis = 3_000)
        assertTrue(gate.shouldRecord(5, "one", 10_000))
        assertTrue(gate.shouldRecord(5, "two", 10_500))
        assertTrue(gate.shouldRecord(6, "two", 11_000))
    }
}
