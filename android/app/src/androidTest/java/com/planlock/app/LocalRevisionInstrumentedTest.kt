package com.planlock.app

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.record.PlanLockDatabase
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real SQLite transactions; no app database, account, network, or permissions are changed. */
class LocalRevisionInstrumentedTest {
    private fun plan(id: Long) = PlanSnapshotEntity(id, 1, "local-$id", "study", "10:00", "11:00", null, null, null, 0, null, 1, 0)

    @Test fun committedBatchNotifiesAcrossStoresAndRollbackDoesNotNotify() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "local-revision-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, PlanLockDatabase::class.java, name).build()
        try {
            val writer = RecordStore(db)
            val reader = RecordStore(db)
            assertSame(writer.localRevision, reader.localRevision)
            val before = reader.localRevision.value
            val written = CompletableDeferred<Unit>()
            val commit = CompletableDeferred<Unit>()
            val transaction = async {
                db.withTransaction {
                    writer.updateLocalPlan(plan(-1))
                    writer.updateLocalPlan(plan(-2))
                    written.complete(Unit)
                    commit.await()
                }
            }
            written.await()
            assertEquals(before, reader.localRevision.value)
            commit.complete(Unit)
            transaction.await()
            val changed = withTimeout(5_000) { reader.localRevision.first { it > before } }
            assertEquals(before + 1, changed)
            assertEquals(2, reader.plans().size)
            assertNull(withTimeoutOrNull(250) { reader.localRevision.first { it > changed } })
            try {
                db.withTransaction {
                    writer.deletePlan(-1)
                    error("rollback")
                }
            } catch (_: IllegalStateException) { }
            assertEquals(2, reader.plans().size)
            assertNull(withTimeoutOrNull(250) { reader.localRevision.first { it > changed } })
            // Direct DAO writes are covered as well as RecordStore and reconciliation writes.
            db.planSnapshotDao().delete(-1)
            withTimeout(5_000) { reader.localRevision.first { it > changed } }
            assertEquals(listOf(-2L), reader.plans().map { it.id })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
