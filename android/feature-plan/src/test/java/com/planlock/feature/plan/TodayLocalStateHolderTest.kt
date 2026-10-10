package com.planlock.feature.plan

import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.SyncOverview
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayLocalStateHolderTest {
    private val key = TodayLocalKey(42, "2026-10-04")

    @Test
    fun `multiple subscribers share one in-flight preload`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        withHolder(reader = reader(plans = {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            emptyList()
        })) { holder ->
            holder.prefetch(key)
            holder.prefetch(key)
            withTimeout(1_000) { started.await() }
            assertEquals(1, calls.get())
            release.complete(Unit)
            val ready = withTimeout(1_000) { holder.state.first { it.phase == TodayLocalPhase.READY } }
            assertEquals(key, ready.key)
        }
    }

    @Test
    fun `cancelled old observer does not cancel process read for replacement observer`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val expected = localData("survives")
        withHolder(reader = reader(plans = {
            started.complete(Unit)
            release.await()
            expected.plans
        })) { holder ->
            holder.prefetch(key)
            val oldObserver = launch { holder.state.first { it.phase == TodayLocalPhase.READY } }
            withTimeout(1_000) { started.await() }
            oldObserver.cancelAndJoin()
            val replacement = async { holder.state.first { it.phase == TodayLocalPhase.READY } }
            release.complete(Unit)
            val ready = withTimeout(1_000) { replacement.await() }
            assertEquals("survives", ready.read?.data?.plans?.single()?.name)
        }
    }

    @Test
    fun `preload deadline falls back and later page refresh is not permanently locked`() = runBlocking {
        val calls = AtomicInteger()
        val never = CompletableDeferred<Unit>()
        withHolder(
            reader = reader(plans = {
                if (calls.incrementAndGet() == 1) never.await()
                emptyList()
            }),
            deadlineMillis = 40,
        ) { holder ->
            holder.prefetch(key)
            val timedOut = withTimeout(1_000) { holder.state.first { it.phase == TodayLocalPhase.TIMED_OUT } }
            assertNull(timedOut.read)

            holder.refresh(key)
            val ready = withTimeout(1_000) { holder.state.first { it.phase == TodayLocalPhase.READY } }
            assertNotNull(ready.read?.data)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `refresh requested before preload timeout is retried immediately after fallback`() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        withHolder(
            reader = reader(plans = {
                if (calls.incrementAndGet() == 1) {
                    started.complete(Unit)
                    never.await()
                }
                localData("fallback").plans
            }),
            deadlineMillis = 500,
        ) { holder ->
            holder.prefetch(key)
            withTimeout(1_000) { started.await() }
            assertEquals(TodayLocalPhase.READING, holder.state.value.phase)
            holder.refresh(key)
            val ready = withTimeout(1_500) { holder.state.first { it.phase == TodayLocalPhase.READY } }
            assertEquals("fallback", ready.read?.data?.plans?.single()?.name)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `failed refresh retains existing content while exposing the error`() = runBlocking {
        val calls = AtomicInteger()
        val initial = localData("kept")
        withHolder(reader = reader(plans = {
            if (calls.incrementAndGet() == 1) initial.plans else throw IllegalStateException("disk")
        })) { holder ->
            holder.prefetch(key)
            val first = withTimeout(1_000) { holder.state.first { it.phase == TodayLocalPhase.READY } }
            val originalRead = requireNotNull(first.read)

            holder.refresh(key)
            val failed = withTimeout(1_000) {
                holder.state.first { it.phase == TodayLocalPhase.READY && calls.get() == 2 && it.read?.error != null }
            }
            assertEquals("kept", failed.read?.data?.plans?.single()?.name)
            assertNotNull(failed.read?.error)
            assertTrue(originalRead !== failed.read)
        }
    }

    @Test
    fun `new user or date cannot receive stale seed from cancelled read`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val secondKey = TodayLocalKey(99, "2026-10-05")
        withHolder(reader = reader(plans = {
            if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                localData("stale").plans
            } else {
                localData("current").plans
            }
        })) { holder ->
            holder.prefetch(key)
            withTimeout(1_000) { firstStarted.await() }
            holder.prefetch(secondKey)
            val ready = withTimeout(1_000) {
                holder.state.first { it.key == secondKey && it.phase == TodayLocalPhase.READY }
            }
            assertEquals("current", ready.read?.data?.plans?.single()?.name)
            assertEquals(secondKey, ready.key)
            releaseFirst.complete(Unit)
        }
    }

    private fun reader(plans: suspend () -> List<PlanSnapshotEntity>) = TodayLocalReader(
        plans = plans,
        schedules = { emptyList() },
        overview = { SyncOverview() },
        weather = { null },
    )

    private fun localData(name: String) = TodayLocalData(
        plans = listOf(PlanSnapshotEntity(7, 42, name, "学习", "21:00", "23:00", null, null, null, 0, null, 1, 0)),
        schedules = emptyList(),
        violations = 0,
        unlocks = 0,
        streak = 0,
        weather = null,
    )

    private suspend fun withHolder(
        reader: TodayLocalReader,
        deadlineMillis: Long = 1_200,
        block: suspend (TodayLocalStateHolder) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(TodayLocalStateHolder(reader, scope, deadlineMillis))
        } finally {
            scope.cancel()
        }
    }
}
