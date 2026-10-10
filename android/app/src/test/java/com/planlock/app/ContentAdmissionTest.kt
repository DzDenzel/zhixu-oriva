package com.planlock.app

import com.planlock.feature.plan.TodayLocalData
import com.planlock.feature.plan.TodayLocalKey
import com.planlock.feature.plan.TodayLocalPhase
import com.planlock.feature.plan.TodayLocalRead
import com.planlock.feature.plan.TodayLocalState
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentAdmissionTest {
    private val key = TodayLocalKey(42, "2026-10-04")
    private val read = TodayLocalRead(
        data = TodayLocalData(emptyList(), emptyList(), 0, 0, 0, null),
        error = null,
    )
    private val ready = TodayLocalState(key, read, TodayLocalPhase.READY)

    @Test
    fun `ordinary animation blocks all content during reveal even when local data is ready`() {
        val decision = startupContentDecision(true, true, false, key, ready)
        assertEquals(StartupContentStage.BLOCKED, decision.stage)
        assertNull(decision.read)
    }

    @Test
    fun `ordinary animation admits cheap real first frame after reveal`() {
        val decision = startupContentDecision(true, true, true, key, ready)
        assertEquals(StartupContentStage.FIRST_FRAME, decision.stage)
        assertSame(read, decision.read)
    }

    @Test
    fun `first frame and full content handoff retain the exact same local read`() {
        val first = startupContentDecision(true, true, true, key, ready)
        val full = startupContentDecision(true, false, true, key, ready)
        assertEquals(StartupContentStage.FULL, full.stage)
        assertSame(first.read, full.read)
    }

    @Test
    fun `reduced or completed splash mounts full content immediately`() {
        val pending = TodayLocalState(key, null, TodayLocalPhase.READING)
        val decision = startupContentDecision(true, false, false, key, pending)
        assertEquals(StartupContentStage.FULL, decision.stage)
        assertNull(decision.read)
    }

    @Test
    fun `deadline fallback does not admit an empty shell before playback completes`() {
        val timedOut = TodayLocalState(key, null, TodayLocalPhase.TIMED_OUT)
        assertEquals(
            StartupContentStage.BLOCKED,
            startupContentDecision(true, true, true, key, timedOut).stage,
        )
        assertEquals(
            StartupContentStage.FULL,
            startupContentDecision(true, false, true, key, timedOut).stage,
        )
    }

    @Test
    fun `read failure is an admitted state distinct from successful empty content`() {
        val failedRead = TodayLocalRead(null, "本机数据读取失败，请重试")
        val failed = TodayLocalState(key, failedRead, TodayLocalPhase.READY)
        val decision = startupContentDecision(true, true, true, key, failed)
        assertEquals(StartupContentStage.FIRST_FRAME, decision.stage)
        assertSame(failedRead, decision.read)
    }

    @Test
    fun `stale identity seed is never admitted`() {
        val otherKey = TodayLocalKey(99, "2026-10-05")
        val decision = startupContentDecision(true, true, true, otherKey, ready)
        assertEquals(StartupContentStage.BLOCKED, decision.stage)
        assertNull(decision.read)
    }
}
