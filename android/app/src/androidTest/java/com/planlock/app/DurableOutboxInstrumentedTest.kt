package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.mock.MockApi
import com.planlock.app.api.dto.*
import com.planlock.app.record.*
import com.planlock.app.record.entity.*
import com.planlock.app.sync.SyncClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Real on-device SQLite transactions; never opens the installed user's database. */
class DurableOutboxInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName in setOf("com.planlock.qareq031", "com.planlock.qapack3a")) { "Isolated QA application required" }
    }
    private fun database(name: String) = Room.databaseBuilder(context, PlanLockDatabase::class.java, name).build()
    private fun plan(id: Long = -101) = PlanSnapshotEntity(id,1,"original","study","10:00:00","11:00:00",null,null,null,15,null,1,0)
    private fun strategy(id: Long = -101) = StrategySnapshotEntity(-102,id,null,"[]",true,false,0)
    private fun schedule(id: Long = -201, linked: Long? = null) = ScheduleSnapshotEntity(id,1,"original","2026-10-09","10:00:00","2026-10-09","11:00:00",false,null,null,null,null,linked,false,"Asia/Shanghai",1,0)
    private fun request(title: String = "original", linked: Long? = null) = ScheduleUpsertRequest(title,"2026-10-09","10:00:00","2026-10-09","11:00:00",linkedPlanId=linked,tzId="Asia/Shanghai")
    private suspend fun withStore(block: suspend (RecordStore, PlanLockDatabase) -> Unit) {
        val name = "outbox-${UUID.randomUUID()}.db"
        val db = database(name)
        try { block(RecordStore(db),db) } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun persistentCreateSurvivesDatabaseCloseAndResponseLoss() = runBlocking {
        val name="restart-${UUID.randomUUID()}.db"
        var db=database(name)
        val remote=Remote()
        try {
            var store=RecordStore(db)
            store.updateLocalPlan(plan(),strategy=strategy())
            val originalIdentity=Json.decodeFromString<SyncPlanItem>(store.pending().first().payload!!).clientId
            remote.dropNext=true
            assertTrue(runCatching { SyncClient(remote,store).pushPendingRecords() }.isFailure)
            db.close(); db=database(name); store=RecordStore(db)
            assertEquals(originalIdentity,Json.decodeFromString<SyncPlanItem>(store.pending().first().payload!!).clientId)
            SyncClient(remote,store).pushPendingRecords()
            assertEquals(1,remote.plans.size)
            assertEquals(listOf(41L),store.plans().map { it.id })
            assertEquals(41L,store.strategy(41)!!.planId)
            assertTrue(store.pending().isEmpty())
            db.close(); db=database(name); store=RecordStore(db)
            store.updateLocalPlan(plan().copy(name="stale-editor"))
            assertEquals(listOf(41L),store.plans().map { it.id })
            assertEquals(41L,store.pending().single().planId)
            store.deletePlan(-101)
            assertNull(store.plan(41)); assertTrue(store.pending().all { it.planId==41L })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun mappingMovesLinkedSchedulesThreeRecordReferencesAndAggregates() = runBlocking { withStore { store,db ->
        store.updateLocalPlan(plan(),strategy=strategy())
        store.saveScheduleChange(schedule(linked=-101),request(linked=-101))
        store.enqueueExecution(ExecutionRecordRequest(-101,"2026-10-09",1,clientId="execution"))
        store.enqueueViolation(ViolationRecordRequest(-101,"2026-10-09T10:00:00",packageName="app",startedAt="2026-10-09T10:00:00",endedAt="2026-10-09T10:01:00",durationMs=60000,clientId="violation"))
        store.enqueueUnlock(UnlockRecordRequest(-101,"2026-10-09T10:01:00",clientId="unlock"))
        val sent=store.pending().first()
        store.acknowledgePlan(sent,41,0)
        assertEquals(listOf(41L),store.plans().map { it.id })
        assertEquals(41L,store.strategy(41)!!.planId)
        assertEquals(41L,store.schedule(-201)!!.linkedPlanId)
        assertTrue(store.pending().filter { it.type in setOf("execution","violation","unlock","strategy_upsert") }.all { it.planId==41L })
        assertEquals(41L,db.behaviorAggregateDao().all().single().planId)
        val queued=Json.decodeFromString<SchedulePendingUpsert>(store.pending().first { it.type=="schedule_upsert" }.payload!!)
        assertEquals(41L,queued.request.linkedPlanId)
    } }

    @Test fun editDuringCreateAcknowledgementRetainsNewerSnapshotAndIntent() = runBlocking { withStore { store,_ ->
        store.updateLocalPlan(plan())
        val sent=store.pending().single()
        store.updateLocalPlan(plan().copy(name="later"))
        store.acknowledgePlan(sent,41,0)
        assertEquals("later",store.plan(41)!!.name)
        assertEquals(1,store.pending().size)
        val next=Json.decodeFromString<SyncPlanItem>(store.pending().single().payload!!)
        assertEquals(41L,next.id); assertEquals(0L,next.baseVersion); assertEquals("later",next.name)
        SyncClient(Remote().also { it.plans[41]=SyncPlanView(id=41,name="original",type="study",version=0) },store).pushPendingRecords()
        assertEquals("later",store.plan(41)!!.name)
        assertTrue(store.pending().isEmpty())
    } }

    @Test fun createThenDeleteWaitsForMappingAndDoesNotResurrect() = runBlocking { withStore { store,_ ->
        store.updateLocalPlan(plan(),strategy=strategy())
        store.deletePlan(-101)
        val remote=Remote(); remote.dropNext=true
        assertTrue(runCatching { SyncClient(remote,store).pushPendingRecords() }.isFailure)
        assertNull(store.plan(-101)); assertEquals(1,remote.plans.size)
        SyncClient(remote,store).pushPendingRecords()
        assertTrue(remote.plans.isEmpty()); assertTrue(store.plans().isEmpty()); assertTrue(store.pending().isEmpty())
    } }

    @Test fun failedSendAndPullCannotOverwriteLocalEditOrDeletedPlan() = runBlocking { withStore { store,_ ->
        store.updateLocalPlan(plan(41).copy(name="local"))
        store.deletePlan(42)
        store.saveSnapshots(listOf(SyncPlanView(id=41,name="remote"),SyncPlanView(id=42,name="resurrected")),emptyList())
        assertEquals("local",store.plan(41)!!.name); assertNull(store.plan(42)); assertEquals(2,store.pending().size)
    } }

    @Test fun scheduleResponseLossEditDeleteUsesOneIdentityAndPreservesLaterEdit() = runBlocking { withStore { store,_ ->
        store.saveScheduleChange(schedule(),request())
        store.saveScheduleChange(schedule().copy(title="later"),request("later"))
        val remote=Remote(); remote.dropNext=true
        assertTrue(runCatching { SyncClient(remote,store).pushPendingRecords() }.isFailure)
        assertEquals("later",store.schedule(-201)!!.title)
        SyncClient(remote,store).pushPendingRecords()
        assertEquals(1,remote.schedules.size); assertEquals("later",remote.schedules.values.single().title)
        assertEquals(listOf(51L),store.schedules().map { it.id }); assertEquals("later",store.schedule(51)!!.title)
        store.enqueueScheduleDelete(51); SyncClient(remote,store).pushPendingRecords()
        assertTrue(remote.schedules.isEmpty()); assertTrue(store.pending().isEmpty())
    } }

    @Test fun lostUpdateResponseConfirmsMatchingRemoteButProtectsDifferentRemote() = runBlocking { withStore { store,_ ->
        val remote=Remote()
        remote.plans[41]=SyncPlanView(id=41,name="original",type="study",startTime="10:00:00",endTime="11:00:00",remindAdvanceMin=15,status=1,version=0)
        store.updateLocalPlan(plan(41).copy(name="updated")); remote.dropNext=true
        assertTrue(runCatching { SyncClient(remote,store).pushPendingRecords() }.isFailure)
        SyncClient(remote,store).pushPendingRecords()
        assertTrue(store.pending().isEmpty()); assertEquals(1L,store.plan(41)!!.version)
        store.updateLocalPlan(store.plan(41)!!.copy(name="my-next"))
        remote.plans[41]=remote.plans[41]!!.copy(name="other-device",version=2)
        SyncClient(remote,store).pushPendingRecords()
        assertEquals("my-next",store.plan(41)!!.name); assertTrue(store.pending().isEmpty())
        assertEquals(1,store.confirmations().size)
    } }

    @Test fun legacyMarkersUpgradeToDurablePlanAndStrategyWithoutLosingProtection() = runBlocking { withStore { store,db ->
        store.saveLocalPlan(plan(41)); store.saveLocalStrategy(strategy(41))
        db.pendingRecordDao().insert(PendingRecordEntity(type="local_plan_change",planId=41,clientId="legacy",payload=null))
        store.prepareOutbox()
        assertEquals(setOf("plan_upsert","strategy_upsert"),store.pending().map { it.type }.toSet())
        assertEquals(setOf(41L),SnapshotProtection.from(store.pending()).plans)
    } }

    @Test fun staleScheduleEditorAndDeleteResolveAcknowledgedId() = runBlocking { withStore { store,_ ->
        store.saveScheduleChange(schedule(),request())
        SyncClient(Remote(),store).pushPendingRecords()
        store.saveScheduleChange(schedule().copy(title="stale-editor"),request("stale-editor"))
        assertEquals(listOf(51L),store.schedules().map { it.id })
        val item=Json.decodeFromString<SchedulePendingUpsert>(store.pending().single().payload!!)
        assertEquals(51L,item.localId); assertEquals(0L,item.baseVersion)
        store.enqueueScheduleDelete(-201)
        assertTrue(store.schedules().isEmpty())
        assertEquals("51",store.pending().last().payload)
    } }

    @Test fun missingCreateMappingCannotAcknowledgeOrLoseProtection() = runBlocking { withStore { store,_ ->
        store.updateLocalPlan(plan())
        val api=object : PlanLockApi by MockApi() {
            override suspend fun syncPush(req: SyncPushRequest)=ApiResponse(data=SyncPushResponse(listOf(SyncItemResult("plan:null",true))))
        }
        assertTrue(runCatching { SyncClient(api,store).pushPendingRecords() }.isFailure)
        assertEquals(-101L,store.plans().single().id); assertEquals(1,store.pending().size)
    } }

    @Test fun responseLossPullProtectsCreateIdentityBeforeIdIsKnown() = runBlocking { withStore { store,_ ->
        store.updateLocalPlan(plan())
        val remote=Remote(); remote.dropNext=true
        val client=SyncClient(remote,store)
        assertTrue(runCatching { client.pushPendingRecords() }.isFailure)
        client.pull()
        assertEquals(listOf(-101L),store.plans().map { it.id })
        assertEquals(1,store.pending().size)
    } }

    @Test fun scheduleResponseLossPullProtectsCreateIdentityBeforeIdIsKnown() = runBlocking { withStore { store,_ ->
        store.saveScheduleChange(schedule(),request())
        val remote=Remote(); remote.dropNext=true
        val client=SyncClient(remote,store)
        assertTrue(runCatching { client.pushPendingRecords() }.isFailure)
        client.pull()
        assertEquals(listOf(-201L),store.schedules().map { it.id })
        assertEquals(1,store.pending().size)
    } }

    @Test fun recoveredLegacyCreatePrecedesItsOlderExecutionDependency() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan())
        store.enqueueExecution(ExecutionRecordRequest(-101,"2026-10-09",1,clientId="legacy-execution"))
        val remote=Remote()
        SyncClient(remote,store).pushPendingRecords()
        assertEquals(listOf(41L),store.plans().map { it.id })
        assertTrue(store.pending().isEmpty())
    } }

    @Test fun pluginMappingDurablyAdoptsUnownedDraftWithoutOverwritingOwnedSettings() {
        val prefs=context.getSharedPreferences("plugin_configuration",android.content.Context.MODE_PRIVATE)
        val old=-99011234L; val canonical=99011234L
        val source="null:$old:4:test:field"
        val target="1:$canonical:4:test:field"
        val otherDraft="null:${old-1}:4:test:field"
        val otherUser="2:$old:4:test:field"
        try {
            check(prefs.edit().putString(source,"offline-value").putString(otherDraft,"other-draft").putString(otherUser,"other-user").remove(target).commit())
            val configurations=com.planlock.plugin.PluginConfigurationStore(context)
            configurations.movePlanDurably(1,old,canonical,adoptUnownedDraft=true)
            assertEquals("offline-value",prefs.getString(target,null))
            assertFalse(prefs.contains(source))
            assertEquals("other-draft",prefs.getString(otherDraft,null))
            assertEquals("other-user",prefs.getString(otherUser,null))
            configurations.movePlanDurably(1,old,canonical,adoptUnownedDraft=true)
            assertEquals("offline-value",prefs.getString(target,null))
            check(prefs.edit().putString(source,"stale-unowned").putString(target,"owned-value").commit())
            configurations.movePlanDurably(1,old,canonical,adoptUnownedDraft=true)
            assertEquals("owned-value",prefs.getString(target,null))
        } finally { prefs.edit().remove(source).remove(target).remove(otherDraft).remove(otherUser).remove("plan-identity:1:$old").commit() }
    }

    @Test fun legacyCsvWhitelistUpgradesToJsonBeforeStrategyTransfer() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan())
        store.saveLocalStrategy(strategy().copy(whitelistApps="com.example.reader,游戏"))
        store.prepareOutbox()
        val item=Json.decodeFromString<SyncStrategyItem>(store.pending().first { it.type=="strategy_upsert" }.payload!!)
        val apps=Json.decodeFromString<List<AppItem>>(item.whitelistApps!!)
        assertEquals(listOf("com.example.reader",""),apps.map { it.packageName })
        assertEquals("游戏",apps.last().name)
        assertEquals(apps,Json.decodeFromString<List<AppItem>>(store.strategy(-101)!!.whitelistApps!!))
    } }

    @Test fun latePluginEditFromOpenNegativeEditorSurvivesMappingAndReopen() {
        val owner=8001L; val old=-99014561L; val canonical=99014561L
        val prefs=context.getSharedPreferences("plugin_configuration",android.content.Context.MODE_PRIVATE)
        val field=com.planlock.plugin.PluginFormField("field","text","Field",kotlinx.serialization.json.JsonPrimitive("default"),"change")
        val first=com.planlock.plugin.PluginConfigurationStore(context)
        try {
            first.save(owner,old,"test",field,"A")
            first.movePlanDurably(owner,old,canonical)
            assertEquals("A",first.values(owner,old,"test",listOf(field))["field"])
            val delayed=com.planlock.plugin.PluginConfigurationStore(context)
            delayed.save(owner,old,"test",field,"B")
            first.movePlanDurably(owner,old,canonical)
            assertEquals("B",com.planlock.plugin.PluginConfigurationStore(context).values(owner,canonical,"test",listOf(field))["field"])
            assertEquals("B",first.values(owner,old,"test",listOf(field))["field"])
        } finally { prefs.edit().remove("$owner:$old:4:test:field").remove("$owner:$canonical:4:test:field").remove("plan-identity:$owner:$old").commit() }
    }

    @Test fun telemetryProducerPausedBeforeInsertCannotStrandNegativeId() = runBlocking { withStore { store,db ->
        store.updateLocalPlan(plan())
        val sent=store.pending().single()
        val continuations=java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher=object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext,block: Runnable) { continuations.add(block) }
        }
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+dispatcher)
        val producer=scope.launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            store.enqueueViolation(ViolationRecordRequest(-101,"2026-10-09T10:00:00",packageName="race",startedAt="2026-10-09T10:00:00",endedAt="2026-10-09T10:01:00",durationMs=60000,clientId="racing-violation"))
        }
        try {
            val paused=checkNotNull(continuations.poll(10,java.util.concurrent.TimeUnit.SECONDS))
            store.acknowledgePlan(sent,41,0)
            paused.run()
            while(!producer.isCompleted) checkNotNull(continuations.poll(10,java.util.concurrent.TimeUnit.SECONDS)).run()
            producer.join()
            assertEquals(41L,store.pending().single().planId)
            assertEquals(41L,db.behaviorAggregateDao().all().single().planId)
            SyncClient(Remote(),store).pushPendingRecords()
            assertTrue(store.pending().isEmpty())
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    } }

    @Test fun pendingDrainDoesNotSelectEntireQueuePerItem() = runBlocking {
        val name="query-bound-${UUID.randomUUID()}.db"
        val selectAll=java.util.concurrent.atomic.AtomicInteger()
        val db=Room.databaseBuilder(context,PlanLockDatabase::class.java,name).setQueryCallback({ sql,_ ->
            if(sql.startsWith("SELECT * FROM pending_record ORDER BY id ASC")) selectAll.incrementAndGet()
        },java.util.concurrent.Executor { it.run() }).build()
        try {
            val store=RecordStore(db)
            repeat(40) { store.enqueueExecution(ExecutionRecordRequest(41,"2026-10-09",1,clientId="query-$it")) }
            selectAll.set(0)
            SyncClient(Remote(),store).pushPendingRecords()
            assertTrue("SELECT-all count=${selectAll.get()}",selectAll.get()<=8)
            assertTrue(store.pending().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun mixedJsonWhitelistKeepsPackageAndCategoryTokens() {
        val method=ExecutionService::class.java.getDeclaredMethod("parseCsv",String::class.java).apply { isAccessible=true }
        val service=ExecutionService()
        val legacy=method.invoke(service,"com.example.reader,游戏") as List<*>
        val json=method.invoke(service,"""[{"name":"Reader","packageName":"com.example.reader"},{"name":"游戏","packageName":""}]""") as List<*>
        assertEquals(listOf("com.example.reader","游戏"),legacy)
        assertEquals(legacy,json)
        assertEquals(emptyList<String>(),method.invoke(service,"[]"))
        assertEquals(emptyList<String>(),method.invoke(service,""))
        assertEquals(listOf("com.example.reader"),method.invoke(service,"""[{"name":"Reader","packageName":"com.example.reader"}]"""))
        assertEquals(listOf("com.example.reader"),method.invoke(service,"com.example.reader"))
    }

    @Test fun capturedPositivePlanAndStrategyFollowOnlyContiguousLocalAcknowledgements() = runBlocking {
        val name="late-positive-${UUID.randomUUID()}.db"; var db=database(name)
        val remote=Remote()
        remote.plans[41]=SyncPlanView(id=41,userId=1,name="original",type="study",startTime="10:00:00",endTime="11:00:00",remindAdvanceMin=15,status=1,version=0)
        remote.strategies[41]=SyncStrategyView(61,41,null,"[]",true,false,0)
        try {
            var store=RecordStore(db); store.saveLocalPlan(plan(41)); store.saveLocalStrategy(strategy(41))
            val captured=store.plan(41)!!; val capturedStrategy=store.strategy(41)!!
            store.updateLocalPlan(captured.copy(name="A"),strategy=capturedStrategy.copy(autoDnd=false))
            SyncClient(remote,store).pushPendingRecords()
            assertEquals(1L,store.plan(41)!!.version); assertEquals(1L,store.strategy(41)!!.version)
            store.updateLocalPlan(store.plan(41)!!.copy(name="A2"),strategy=store.strategy(41)!!.copy(notifySilence=true))
            SyncClient(remote,store).pushPendingRecords(); assertTrue(store.pending().isEmpty())
            db.close(); db=database(name); store=RecordStore(db)
            store.updateLocalPlan(captured.copy(name="B"),strategy=capturedStrategy.copy(notifySilence=true))
            assertEquals(2L,Json.decodeFromString<SyncPlanItem>(store.pending().first { it.type=="plan_upsert" }.payload!!).baseVersion)
            assertEquals(2L,Json.decodeFromString<SyncStrategyItem>(store.pending().first { it.type=="strategy_upsert" }.payload!!).baseVersion)
            SyncClient(remote,store).pushPendingRecords()
            assertEquals("B",remote.plans[41]!!.name); assertTrue(store.pending().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun capturedPositiveScheduleUsesAcknowledgedVersionAfterRestart() = runBlocking {
        val name="late-schedule-${UUID.randomUUID()}.db"; var db=database(name); val remote=Remote()
        remote.createSchedule(request().copy(clientId="seed"))
        try {
            var store=RecordStore(db); store.saveLocalSchedule(schedule(51)); val captured=store.schedule(51)!!
            store.saveScheduleChange(captured.copy(title="A"),request("A")); SyncClient(remote,store).pushPendingRecords()
            assertEquals(1L,store.schedule(51)!!.version); assertTrue(store.pending().isEmpty())
            db.close(); db=database(name); store=RecordStore(db)
            store.saveScheduleChange(captured.copy(title="B"),request("B"))
            assertEquals(1L,Json.decodeFromString<SchedulePendingUpsert>(store.pending().single().payload!!).baseVersion)
            SyncClient(remote,store).pushPendingRecords(); assertEquals("B",remote.schedules[51]!!.title); assertTrue(store.pending().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun pulledDifferentVersionsDoNotBecomeLocalAcknowledgementLineage() = runBlocking { withStore { store,_ ->
        val remote=Remote(); remote.plans[41]=SyncPlanView(id=41,userId=1,name="original",type="study",startTime="10:00:00",endTime="11:00:00",remindAdvanceMin=15,status=1,version=0)
        remote.strategies[41]=SyncStrategyView(61,41,null,"[]",true,false,0); remote.createSchedule(request().copy(clientId="seed"))
        store.saveLocalPlan(plan(41)); store.saveLocalStrategy(strategy(41)); store.saveLocalSchedule(schedule(51))
        val captured=store.plan(41)!!; val st=store.strategy(41)!!; val sc=store.schedule(51)!!
        store.updateLocalPlan(captured.copy(name="A"),strategy=st.copy(autoDnd=false)); store.saveScheduleChange(sc.copy(title="A"),request("A"))
        val client=SyncClient(remote,store); client.pushPendingRecords()
        remote.plans[41]=remote.plans[41]!!.copy(name="other-device",version=2)
        remote.strategies[41]=remote.strategies[41]!!.copy(whitelistApps="[{\"name\":\"other\",\"packageName\":\"com.other\"}]",version=2)
        remote.schedules[51]=remote.schedules[51]!!.copy(title="other-device",version=2)
        client.pull()
        store.updateLocalPlan(captured.copy(name="B"),strategy=st.copy(notifySilence=true)); store.saveScheduleChange(sc.copy(title="B"),request("B"))
        assertEquals(0L,Json.decodeFromString<SyncPlanItem>(store.pending().first { it.type=="plan_upsert" }.payload!!).baseVersion)
        assertEquals(0L,Json.decodeFromString<SyncStrategyItem>(store.pending().first { it.type=="strategy_upsert" }.payload!!).baseVersion)
        assertEquals(0L,Json.decodeFromString<SchedulePendingUpsert>(store.pending().first { it.type=="schedule_upsert" }.payload!!).baseVersion)
        client.pushPendingRecords()
        assertEquals("other-device",remote.plans[41]!!.name); assertEquals("other-device",remote.schedules[51]!!.title)
        assertTrue(store.pending().isEmpty()); assertEquals(2,store.confirmations().size)
        assertEquals("B",store.confirmations().first { it.kind=="plan" }.localPlan!!.name)
        assertEquals("B",store.confirmations().first { it.kind=="schedule" }.localSchedule!!.title)
    } }

    @Test fun equalPayloadRemoteVersionGapDoesNotProveLocalVersionSteps() = runBlocking { withStore { store,_ ->
        val captured=plan(41); val remote=Remote()
        remote.plans[41]=SyncPlanView(id=41,userId=1,name="A",type="study",startTime="10:00:00",endTime="11:00:00",remindAdvanceMin=15,status=1,version=3)
        store.updateLocalPlan(captured.copy(name="A")); SyncClient(remote,store).pushPendingRecords()
        assertEquals(3L,store.plan(41)!!.version); assertTrue(store.pending().isEmpty())
        store.updateLocalPlan(captured.copy(name="B"))
        assertEquals(0L,Json.decodeFromString<SyncPlanItem>(store.pending().single().payload!!).baseVersion)
        SyncClient(remote,store).pushPendingRecords()
        assertEquals("A",remote.plans[41]!!.name)
        assertEquals("B",store.confirmations().single().localPlan!!.name)
        assertTrue(store.pending().isEmpty())
    } }

    @Test fun failedLinkedScheduleRetriesDetachedAfterPositiveOrMappedParentDeletion() = runBlocking {
        for(parent in listOf(41L,-101L)) withStore { store,_ ->
            val remote=Remote()
            if(parent>0) { store.saveLocalPlan(plan(parent)); remote.plans[parent]=SyncPlanView(id=parent,name="original",version=0) }
            else store.updateLocalPlan(plan(parent))
            store.saveScheduleChange(schedule(linked=parent),request(linked=parent))
            val key=Json.decodeFromString<SchedulePendingUpsert>(store.pending().first { it.type=="schedule_upsert" }.payload!!).request.clientId
            store.deletePlan(parent); remote.failNextSchedule=true
            assertTrue(runCatching { SyncClient(remote,store).pushPendingRecords() }.isFailure)
            assertTrue(remote.plans.isEmpty()); assertNull(store.schedule(-201)!!.linkedPlanId)
            val pending=Json.decodeFromString<SchedulePendingUpsert>(store.pending().single().payload!!)
            assertNull(pending.request.linkedPlanId); assertEquals(key,pending.request.clientId)
            SyncClient(remote,store).pushPendingRecords()
            assertNull(remote.schedules.values.single().linkedPlanId); assertTrue(store.pending().isEmpty())
        }
    }

    @Test fun staleLinkedScheduleEditorCannotReattachAcknowledgedDeletedParent() = runBlocking {
        val name="deleted-link-${UUID.randomUUID()}.db"; var db=database(name); val remote=Remote()
        try {
            var store=RecordStore(db); store.saveLocalPlan(plan(41)); remote.plans[41]=SyncPlanView(id=41,name="original",version=0)
            store.deletePlan(41); SyncClient(remote,store).pushPendingRecords(); db.close(); db=database(name); store=RecordStore(db)
            store.saveScheduleChange(schedule(linked=41),request(linked=41))
            assertNull(store.schedule(-201)!!.linkedPlanId)
            assertNull(Json.decodeFromString<SchedulePendingUpsert>(store.pending().single().payload!!).request.linkedPlanId)
            SyncClient(remote,store).pushPendingRecords(); assertTrue(store.pending().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun stalePauseChangesOnlyRequestedStatusOnCurrentLocalPlan() = runBlocking { withStore { store,_ ->
        val captured=plan(41); store.saveLocalPlan(captured)
        store.updateLocalPlan(captured.copy(name="new-local-name",color="new-color"))
        val sent=store.pending().single(); store.acknowledgePlan(sent,41,1)
        val data=object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data=kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences)=transform(androidx.datastore.preferences.core.emptyPreferences())
        }
        val gate=com.planlock.feature.plan.PlanActionGate(store,Remote(),com.planlock.app.security.BackupPasswordStore(data),nowMillis={0L})
        assertTrue(gate.perform(captured,com.planlock.feature.plan.ProtectedPlanAction.PAUSE,null) is com.planlock.feature.plan.PlanActionResult.Completed)
        assertEquals("new-local-name",store.plan(41)!!.name); assertEquals("new-color",store.plan(41)!!.color); assertEquals(0,store.plan(41)!!.status)
        assertEquals(1L,Json.decodeFromString<SyncPlanItem>(store.pending().single().payload!!).baseVersion)
    } }

    @Test fun failedExistingLinkedScheduleUpdateRetriesDetachedAfterParentDeletion() = runBlocking { withStore { store,_ ->
        val remote=Remote(); remote.plans[41]=SyncPlanView(id=41,name="original",version=0); store.saveLocalPlan(plan(41))
        remote.createSchedule(request(linked=41).copy(clientId="seed")); store.saveLocalSchedule(schedule(51,41))
        store.saveScheduleChange(schedule(51,41).copy(title="changed"),request("changed",41)); store.deletePlan(41); remote.failNextSchedule=true
        assertTrue(runCatching { SyncClient(remote,store).pushPendingRecords() }.isFailure)
        assertTrue(remote.plans.isEmpty()); assertNull(remote.schedules[51]!!.linkedPlanId)
        assertNull(Json.decodeFromString<SchedulePendingUpsert>(store.pending().single().payload!!).request.linkedPlanId)
        SyncClient(remote,store).pushPendingRecords(); assertEquals("changed",remote.schedules[51]!!.title); assertNull(remote.schedules[51]!!.linkedPlanId); assertTrue(store.pending().isEmpty())
    } }

    @Test fun firstNullStrategyVersionAcknowledgementIsIndependentOfPlanVersion() = runBlocking { withStore { store,_ ->
        val captured=plan(41).copy(version=7); val capturedStrategy=strategy(41).copy(version=null)
        val remote=Remote(); remote.plans[41]=SyncPlanView(id=41,name="original",type="study",startTime="10:00:00",endTime="11:00:00",remindAdvanceMin=15,status=1,version=7)
        store.saveLocalPlan(captured); store.updateLocalPlan(captured,strategy=capturedStrategy)
        SyncClient(remote,store).pushPendingRecords(); assertEquals(8L,store.plan(41)!!.version); assertEquals(0L,store.strategy(41)!!.version)
        store.updateLocalPlan(captured.copy(name="B"),strategy=capturedStrategy.copy(notifySilence=true))
        assertEquals(8L,Json.decodeFromString<SyncPlanItem>(store.pending().first { it.type=="plan_upsert" }.payload!!).baseVersion)
        assertEquals(0L,Json.decodeFromString<SyncStrategyItem>(store.pending().first { it.type=="strategy_upsert" }.payload!!).baseVersion)
        SyncClient(remote,store).pushPendingRecords(); assertTrue(store.pending().isEmpty())
    } }

    @Test fun mappedActivePlanKeepsExactlyOneStartRecordAndNoSyntheticEnd() = runBlocking { withStore { store,_ ->
        val cached=plan(); val factory=RecordEventFactory(); val now=System.currentTimeMillis()
        store.updateLocalPlan(cached); store.enqueueExecution(factory.executionStarted(cached,now))
        com.planlock.app.engine.ExecutionEngine.start(-101,emptySet(),emptySet())
        try {
            store.acknowledgePlan(store.pending().first { it.type=="plan_upsert" },41,0)
            val active=store.plan(41)!!
            val identity=activePlanIdentity(com.planlock.app.engine.ExecutionEngine.activePlanId(),cached,store::resolvePlanId)
            val edges=ExecutionEdgeDetector.detect(identity.previousId,active.id)
            if(edges.startedPlanId!=null) store.enqueueExecution(factory.executionStarted(active,now))
            if(edges.endedPlanId!=null) store.enqueueExecution(factory.executionEnded(identity.cachedPlan!!,now,false))
            com.planlock.app.engine.ExecutionEngine.start(active.id,emptySet(),emptySet())
            val records=store.pending().filter { it.type=="execution" }
            assertEquals(1,records.size); assertEquals(41L,records.single().planId); assertEquals(RecordEventFactory.STATUS_STARTED,records.single().status)
            assertEquals(41L,com.planlock.app.engine.ExecutionEngine.activePlanId())
        } finally { com.planlock.app.engine.ExecutionEngine.stop() }
    } }

    internal class Remote : PlanLockApi by MockApi() {
        val plans=linkedMapOf<Long,SyncPlanView>()
        val schedules=linkedMapOf<Long,ScheduleDto>()
        private val planIds=mutableMapOf<String,Long>()
        private val scheduleIds=mutableMapOf<String,Long>()
        val strategies=mutableMapOf<Long,SyncStrategyView>()
        var failNextSchedule=false
        var dropNext=false
        private fun dropped() { if(dropNext) { dropNext=false; throw IOException("Response lost after commit") } }
        override suspend fun syncPush(req: SyncPushRequest): ApiResponse<SyncPushResponse> {
            val result=req.plans?.singleOrNull()?.let { sent ->
                val id=sent.id ?: planIds.getOrPut(requireNotNull(sent.clientId)) { 41L }
                val previous=plans[id]
                if(sent.id != null && previous?.version != sent.baseVersion) return ApiResponse(data=SyncPushResponse(listOf(SyncItemResult("plan:${sent.id}",false,1409,"conflict"))))
                if(sent.id!=null || previous==null) plans[id]=SyncPlanView(id,1,sent.name,sent.type,sent.startTime,sent.endTime,sent.repeatRule,sent.color,sent.icon,sent.remindAdvanceMin ?: 15,sent.remindMethods,sent.status ?: 1,if(sent.id==null) 0 else (sent.baseVersion ?: 0)+1,sent.clientId)
                SyncItemResult("plan:${sent.id}",true,id=id,version=plans[id]!!.version,clientId=sent.clientId)
            } ?: req.strategies!!.single().let { sent ->
                val previous=strategies[sent.planId]
                if(previous!=null && previous.version!=sent.baseVersion) return ApiResponse(data=SyncPushResponse(listOf(SyncItemResult("strategy:${sent.planId}",false,1409,"conflict"))))
                val version=if(previous==null) 0 else (previous.version ?: 0)+1
                strategies[sent.planId]=SyncStrategyView(61,sent.planId,null,sent.whitelistApps,sent.autoDnd,sent.notifySilence,version)
                SyncItemResult("strategy:${sent.planId}",true,id=61,version=version)
            }
            dropped(); return ApiResponse(data=SyncPushResponse(listOf(result)))
        }
        override suspend fun syncPull()=ApiResponse(data=SyncPullResponse(plans=plans.values.toList(),strategies=strategies.values.toList(),schedules=schedules.values.map { SyncScheduleView(it.id,it.userId,it.title,it.startDate,it.startTime,it.endDate,it.endTime,it.allDay,it.location,it.note,it.remindAdvanceMin,it.repeatRule,it.linkedPlanId,it.tzId,it.status,it.version,it.clientId) }))
        override suspend fun deletePlan(id: Long, baseVersion: Long?): ApiResponse<Unit> { if (baseVersion != null && plans[id] != null && plans[id]?.version != baseVersion) return ApiResponse(code=1409); plans.remove(id); strategies.remove(id); schedules.replaceAll { _,s -> if(s.linkedPlanId==id) s.copy(linkedPlanId=null) else s }; return ApiResponse(data=Unit) }
        override suspend fun createSchedule(req: ScheduleUpsertRequest): ApiResponse<ScheduleDto> {
            if(failNextSchedule) { failNextSchedule=false; throw IOException("schedule temporarily offline") }
            if(req.linkedPlanId!=null && req.linkedPlanId !in plans) return ApiResponse(code=1002,message="foreign key")
            val id=scheduleIds.getOrPut(requireNotNull(req.clientId)) { 51L }
            val saved=schedules.getOrPut(id) { dto(id,0,req) }
            dropped(); return ApiResponse(data=saved)
        }
        override suspend fun updateSchedule(id: Long,baseVersion: Long,req: ScheduleUpsertRequest): ApiResponse<ScheduleDto> {
            if(failNextSchedule) { failNextSchedule=false; throw IOException("schedule temporarily offline") }
            if(req.linkedPlanId!=null && req.linkedPlanId !in plans) return ApiResponse(code=1002,message="foreign key")
            if(schedules[id]?.version!=baseVersion) return ApiResponse(code=1409,message="conflict")
            val saved=dto(id,baseVersion+1,req); schedules[id]=saved; dropped(); return ApiResponse(data=saved)
        }
        override suspend fun deleteSchedule(id: Long, baseVersion: Long?): ApiResponse<Unit> { if (baseVersion != null && schedules[id] != null && schedules[id]?.version != baseVersion) return ApiResponse(code=1409); schedules.remove(id); return ApiResponse(data=Unit) }
        private fun dto(id: Long,version: Long,r: ScheduleUpsertRequest)=ScheduleDto(id,1,r.title,r.startDate,r.startTime,r.endDate,r.endTime,r.allDay,r.location,r.note,r.remindAdvanceMin,r.repeatRule,r.linkedPlanId,r.tzId,1,version,r.clientId)
    }
}
