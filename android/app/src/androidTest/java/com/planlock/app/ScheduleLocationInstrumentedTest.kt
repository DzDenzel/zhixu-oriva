package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.dto.*
import com.planlock.app.record.*
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScheduleLocationInstrumentedTest {
    @Test fun coordinatesPersistInSnapshotOutboxAcknowledgementAndPullWithoutCrossingScope() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context,PlanLockDatabase::class.java).build()
        try {
            val store = RecordStore(db,{ "location-test\n1" })
            val request = ScheduleUpsertRequest("见面","2026-10-19",endDate="2026-10-19",allDay=true,location="人民广场",locationLat=31.2304,locationLon=121.4737)
            val row = ScheduleSnapshotEntity(-21101,1,request.title,request.startDate,null,request.endDate,null,true,request.location,null,null,null,null,false,null,1,0,locationLat=request.locationLat,locationLon=request.locationLon)
            store.saveScheduleChange(row,request)
            val pending=store.pending().single()
            assertTrue(pending.payload!!.contains("31.2304"))
            store.acknowledgeSchedule(pending,21101,1)
            assertEquals(31.2304,store.schedules().single().locationLat!!,0.0)
            assertTrue(store.pending().isEmpty())
            store.saveSnapshots(emptyList(),emptyList(),listOf(SyncScheduleView(id=21101,userId=1,title="见面",startDate=request.startDate,endDate=request.endDate,allDay=true,location="新地点",locationLat=31.2404,locationLon=121.4837,version=2)))
            assertEquals(31.2404,store.schedules().single().locationLat!!,0.0)
            assertTrue(store.forScope("location-test\n2").schedules().isEmpty())
            try { store.saveScheduleChange(row,request.copy(locationLon=null));fail("Unpaired coordinate") } catch (_:IllegalArgumentException) { }
            assertTrue(store.pending().isEmpty())
        } finally { db.close() }
    }
}
