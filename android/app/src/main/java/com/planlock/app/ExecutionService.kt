package com.planlock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.planlock.app.api.COLOROS_BASIC_TOOLS
import com.planlock.app.api.DEFAULT_BASIC_TOOLS
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.dnd.DndController
import com.planlock.app.engine.AppPackageResolver
import com.planlock.app.engine.AppExemptionResolver
import com.planlock.app.engine.DndFeedbackGate
import com.planlock.app.engine.ExecutionEngine
import com.planlock.app.engine.ForegroundAppDetector
import com.planlock.app.engine.ObservationSourcePolicy
import com.planlock.app.engine.ExecutionFeedback
import com.planlock.app.engine.FeedbackKind
import com.planlock.app.engine.executionHaptic
import com.planlock.app.record.RecordStore
import com.planlock.app.record.RecordEventFactory
import com.planlock.app.record.ForegroundObservation
import com.planlock.app.record.ForegroundUsageIntervalTracker
import com.planlock.app.record.ObservationSource
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.AppSync
import com.planlock.app.security.AccessibilityHealthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

/**
 * 执行调度前台服务（BUG-008）：周期评估 Room 计划 + 本地时间（含跨天），
 * 计划进入执行时段 → ExecutionEngine.start（观测）+ 免打扰；退出 → stop + 还原。
 */
class ExecutionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var recordStore: RecordStore
    private lateinit var resolver: AppPackageResolver
    private lateinit var exemptionResolver: AppExemptionResolver
    private lateinit var dnd: DndController
    private lateinit var syncClient: AppSync
    private lateinit var connectivity: ConnectivityManager
    private lateinit var foregroundDetector: ForegroundAppDetector
    private lateinit var accessibilityHealthStore: AccessibilityHealthStore
    private val fallbackIntervals = ForegroundUsageIntervalTracker()
    private val recordFactory = RecordEventFactory()
    private val evaluateMutex = Mutex()
    private val evaluationRequests = AtomicLong()
    @Volatile private var latestStartId = 0
    private var lastActivePlan: PlanSnapshotEntity? = null
    private var executionScope: String? = null
    private var restoredExecutions = false
    private var lastPeriodicSyncAt = 0L
    private val dndFeedbackGate = DndFeedbackGate(FEEDBACK_INTERVAL_MS)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                try { syncClient.pushPendingRecords() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Pending records remain available for the next retry. */ }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            requestEvaluation()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        recordStore = RecordStore(this)
        syncClient = AppSync.get(this)
        resolver = AppPackageResolver(this)
        exemptionResolver = AppExemptionResolver(this, DEFAULT_BASIC_TOOLS + COLOROS_BASIC_TOOLS)
        dnd = DndController(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        foregroundDetector = ForegroundAppDetector(this)
        accessibilityHealthStore = AccessibilityHealthStore(this)
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivity.registerNetworkCallback(request, networkCallback)
        running = this
        startForegroundCompat()
        handler.postDelayed(tick, TICK_MS)
        scope.launch { com.planlock.app.api.TokenStore.state.collect { requestEvaluation() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // App resume/permission return should reconcile immediately instead of
        // waiting for the next 15-second tick.
        latestStartId = startId
        requestEvaluation()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        android.util.Log.i("OrivaExecution", "service-destroyed")
        handler.removeCallbacks(tick)
        if (running === this) running = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        exemptionResolver.close()
        ExecutionEngine.stop()
        NotificationSilenceController.setRequired(false)
        dnd.reconcileBlocking(null)
        super.onDestroy()
    }

    private fun requestEvaluation() {
        val request = evaluationRequests.incrementAndGet()
        scope.launch { evaluate(request) }
    }

    private suspend fun evaluate(request: Long) = evaluateMutex.withLock { syncClient.withLocalMutation {
        if (request != evaluationRequests.get()) return@withLocalMutation
        val currentScope = recordStore.currentScope()
        if (executionScope != currentScope) {
            executionScope?.let { previousScope ->
                val previousStore = recordStore.forScope(previousScope)
                lastActivePlan?.let { previousStore.finishOccurrence(it.id, System.currentTimeMillis()) }
                fallbackIntervals.close(System.currentTimeMillis())?.let { previousStore.enqueueViolation(recordFactory.behavior(it)) }
            }
            ExecutionEngine.stop()
            NotificationSilenceController.setRequired(false)
            lastActivePlan = null
            restoredExecutions = false
            dnd.reconcile(null)
            executionScope = currentScope
        }
        val rawPreviousId = ExecutionEngine.activePlanId()
        var feedbackPlanId = rawPreviousId ?: 0L
        try {
            val cal = Calendar.getInstance()
            val evaluatedAt = cal.timeInMillis
            val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val occurrenceDate = OccurrenceDate.from(cal)
            val allPlans = recordStore.plans()
            val enabled = allPlans.filter { it.status == 1 }
            val selected = enabled.filter { PlanOccurrence.isActive(it, nowMinutes, occurrenceDate) }.maxByOrNull { it.id }
            val identity = activePlanIdentity(rawPreviousId, lastActivePlan, recordStore::resolvePlanId, selected?.id)
            val previousId = identity.previousId
            feedbackPlanId = previousId ?: 0L
            val candidate = selected?.copy(id = requireNotNull(identity.activeId))
            val existingStart = candidate?.let { recordStore.activeExecution(it.id) }
            // The transaction rechecks status/endedOccurrenceDate and reuses a persisted start after restart.
            val active = candidate?.takeIf { recordStore.beginOccurrence(it.id, evaluatedAt) }
            val previousPlan = allPlans.firstOrNull { it.id == previousId }
                ?: identity.cachedPlan?.takeIf { it.id == previousId }

            if (!restoredExecutions) {
                // Recover executions left open while the sticky service/process was absent.
                // RecordStore retains their original planDate, including a previous-day overnight start.
                allPlans.filter { it.id != active?.id && it.id != previousId }.forEach { plan ->
                    if (recordStore.activeExecution(plan.id) != null) PlanCompletion.finish(this, recordStore, plan, evaluatedAt)
                }
                restoredExecutions = true
            }

            // Close the outgoing record before platform calls, so a DND/observer failure cannot lose its edge.
            if (previousId != null && previousId != active?.id) {
                val completed = previousPlan?.status == 1 && !PlanOccurrence.isWithinOccurrence(previousPlan, nowMinutes, occurrenceDate)
                // False means the UI transaction already ended it; never add a duplicate completion edge.
                if (previousPlan != null && PlanCompletion.finish(this, recordStore, previousPlan, evaluatedAt)) {
                    if (!completed) ExecutionFeedback.emit(previousId, FeedbackKind.PAUSED)
                }
            }

            if (active != null) {
                feedbackPlanId = active.id
                val strategy = recordStore.strategy(active.id)
                NotificationSilenceController.setRequired(strategy?.notifySilence == true)
                val whitePkgs = resolver.resolve(parseCsv(strategy?.whitelistApps))
                val basicToolsEnabled = planLockPreferences.data.first()[PlanLockPreferenceKeys.basicToolsEnabled] ?: true
                val exemptionSnapshot = exemptionResolver.resolveSnapshot(basicToolsEnabled)
                ExecutionEngine.start(
                    planId = active.id,
                    whitelistPackages = whitePkgs,
                    exemptPackages = exemptionSnapshot.exemptPackages,
                    classifiedPackages = exemptionSnapshot.classifiedPackages,
                    exemptionSnapshotGeneration = exemptionSnapshot.generation,
                )
                observeFallbackIfNeeded(active.id)
                if (previousId != active.id && existingStart == null) {
                    ExecutionFeedback.emit(active.id, FeedbackKind.STARTED)
                    executionHaptic(this)
                }
                val dndNeededPlanId = active.id.takeIf { strategy?.autoDnd == true }
                if (dnd.reconcile(dndNeededPlanId) == com.planlock.app.dnd.DndResult.PermissionMissing) {
                    emitDndPermissionMissing(active.id)
                }
            } else {
                fallbackIntervals.close(System.currentTimeMillis())?.let {
                    recordStore.enqueueViolation(recordFactory.behavior(it))
                }
                exemptionResolver.close()
                ExecutionEngine.stop()
                NotificationSilenceController.setRequired(false)
                if (dnd.reconcile(null) == com.planlock.app.dnd.DndResult.PermissionMissing) {
                    emitDndPermissionMissing(previousId ?: 0L)
                }
            }
            lastActivePlan = active
            val now = System.currentTimeMillis()
            if (now - lastPeriodicSyncAt >= SYNC_INTERVAL_MS) {
                lastPeriodicSyncAt = now
                if (recordStore.pending().isNotEmpty()) syncClient.localChangesCommitted()
            }
            if (active == null) withContext(Dispatchers.Main.immediate) {
                // A stale idle evaluation must not stop a newer start or local-commit request.
                if (request == evaluationRequests.get() && stopSelfResult(latestStartId)) {
                    android.util.Log.i("OrivaExecution", "idle-service-stopped")
                    if (running === this@ExecutionService) running = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            emitDndPermissionMissing(feedbackPlanId)
        } catch (error: Exception) {
            // A single evaluation failure must not kill the sticky foreground service.
            android.util.Log.w("OrivaExecution", "Execution evaluation failed", error)
        }
    } }

    private fun emitDndPermissionMissing(planId: Long) {
        if (dndFeedbackGate.shouldEmit(planId, System.currentTimeMillis())) {
            ExecutionFeedback.emit(planId, FeedbackKind.DND_PERMISSION_MISSING)
        }
    }

    private suspend fun observeFallbackIfNeeded(planId: Long) {
        val now = System.currentTimeMillis()
        val source = ObservationSourcePolicy.select(accessibilityHealthStore.read(), now)
        if (source == ObservationSource.ACCESSIBILITY) {
            fallbackIntervals.close(now)?.let {
                recordStore.enqueueViolation(recordFactory.behavior(it))
            }
            return
        }
        val packageName = foregroundDetector.currentForegroundPackage()
        val decision = ExecutionEngine.inspect(packageName)
        if (packageName == null || decision == null) {
            fallbackIntervals.close(now)?.let { recordStore.enqueueViolation(recordFactory.behavior(it)) }
            return
        }
        val appName = if (decision.shouldTrack) runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull() else null
        fallbackIntervals.observe(
            ForegroundObservation(planId, packageName, appName, decision.shouldTrack, source),
            now,
        )?.let { recordStore.enqueueViolation(recordFactory.behavior(it)) }
    }

    private fun parseCsv(s: String?): List<String> = com.planlock.app.record.StrategyWhitelistPayload.tokens(s)

    private fun startForegroundCompat() {
        val channelId = "planlock-exec"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "计划进行中", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(PlanNotificationCopy.ongoing)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .build()
        startForeground(1, notification)
        android.util.Log.i("OrivaExecution", "foreground-notification-started")
    }

    companion object {
        @Volatile private var running: ExecutionService? = null

        /** Idle has no service/notification. Future starts are owned by OS alarms. */
        internal suspend fun reconcile(context: Context) {
            running?.let { it.requestEvaluation(); return }
            if (!AppVisibility.foreground) return
            withContext(Dispatchers.IO) {
                val clock = Calendar.getInstance()
                val minute = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
                val date = OccurrenceDate.from(clock)
                if (RecordStore(context).plans().any { PlanOccurrence.isActive(it, minute, date) } && AppVisibility.foreground) {
                    runCatching { ContextCompat.startForegroundService(context, Intent(context, ExecutionService::class.java)) }
                        .onFailure { android.util.Log.w("OrivaExecution", "Execution service start deferred", it) }
                }
            }
        }

        private const val TICK_MS = 15_000L
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
        private const val FEEDBACK_INTERVAL_MS = 60 * 60 * 1000L
    }
}
