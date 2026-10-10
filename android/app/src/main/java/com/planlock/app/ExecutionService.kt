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
import com.planlock.app.record.ExecutionEdgeDetector
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
import java.util.Calendar

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
    private var lastActivePlan: PlanSnapshotEntity? = null
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
            scope.launch { evaluate() }
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
        startForegroundCompat()
        handler.postDelayed(tick, TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // App resume/permission return should reconcile immediately instead of
        // waiting for the next 15-second tick.
        scope.launch { evaluate() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        exemptionResolver.close()
        ExecutionEngine.stop()
        dnd.reconcileBlocking(null)
        super.onDestroy()
    }

    private suspend fun evaluate() = evaluateMutex.withLock {
        val rawPreviousId = ExecutionEngine.activePlanId()
        var feedbackPlanId = rawPreviousId ?: 0L
        try {
            val cal = Calendar.getInstance()
            val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val occurrenceDate = OccurrenceDate.from(cal)
            val allPlans = recordStore.plans()
            val enabled = allPlans.filter { it.status == 1 }
            val selected = enabled.filter { PlanOccurrence.isActive(it, nowMinutes, occurrenceDate) }.maxByOrNull { it.id }
            val identity = activePlanIdentity(rawPreviousId, lastActivePlan, recordStore::resolvePlanId, selected?.id)
            val previousId = identity.previousId
            feedbackPlanId = previousId ?: 0L
            val active = selected?.copy(id = requireNotNull(identity.activeId))
            val edges = ExecutionEdgeDetector.detect(previousId, active?.id)
            val previousPlan = identity.cachedPlan?.takeIf { it.id == edges.endedPlanId }
                ?: allPlans.firstOrNull { it.id == edges.endedPlanId }

            if (active != null) {
                feedbackPlanId = active.id
                val strategy = recordStore.strategy(active.id)
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
                if (previousId != active.id) {
                    recordStore.enqueueExecution(recordFactory.executionStarted(active, System.currentTimeMillis()))
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
                if (dnd.reconcile(null) == com.planlock.app.dnd.DndResult.PermissionMissing) {
                    emitDndPermissionMissing(previousId ?: 0L)
                }
            }
            // 连续两个计划交接时也要反馈上一个的结束，不能只在“无下一项”时庆祝。
            if (previousId != null && previousId != active?.id) {
                val previous = previousPlan
                val completed = previous?.status == 1 && !PlanOccurrence.isActive(previous, nowMinutes, occurrenceDate)
                previous?.let {
                    recordStore.enqueueExecution(recordFactory.executionEnded(it, System.currentTimeMillis(), completed))
                }
                ExecutionFeedback.emit(previousId, if (completed) FeedbackKind.COMPLETED else FeedbackKind.PAUSED)
                if (completed) executionHaptic(this)
            }
            lastActivePlan = active
            val now = System.currentTimeMillis()
            if (now - lastPeriodicSyncAt >= SYNC_INTERVAL_MS) {
                lastPeriodicSyncAt = now
                syncClient.pushPendingRecords()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            emitDndPermissionMissing(feedbackPlanId)
        } catch (_: Exception) {
            // A single evaluation failure must not kill the sticky foreground service.
        }
    }

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
                NotificationChannel(channelId, "执行调度", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("执行调度中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .build()
        startForeground(1, notification)
    }

    companion object {
        private const val TICK_MS = 15_000L
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
        private const val FEEDBACK_INTERVAL_MS = 60 * 60 * 1000L
    }
}
