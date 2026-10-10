package com.planlock.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 计划执行期的唯一观测判定入口；只回答“是否记录”，不执行拦截动作。 */
object ExecutionEngine {
    private data class State(
        val planId: Long,
        val whitelist: Set<String>,
        val exemptions: Set<String>,
        val classifiedPackages: Set<String>,
        val exemptionSnapshotGeneration: Long?,
    )

    @Volatile private var state: State? = null
    private val _stateVersion = MutableStateFlow(0L)

    val stateVersion: StateFlow<Long> = _stateVersion.asStateFlow()

    fun start(
        planId: Long,
        whitelistPackages: Set<String>,
        exemptPackages: Set<String>,
        classifiedPackages: Set<String> = emptySet(),
        exemptionSnapshotGeneration: Long? = null,
    ) {
        val next = State(
            planId = planId,
            whitelist = whitelistPackages.toSet(),
            exemptions = exemptPackages.toSet(),
            classifiedPackages = classifiedPackages.toSet(),
            exemptionSnapshotGeneration = exemptionSnapshotGeneration,
        )
        if (state != next) {
            state = next
            _stateVersion.update { it + 1 }
        }
    }

    fun stop() {
        if (state != null) {
            state = null
            _stateVersion.update { it + 1 }
        }
    }

    fun isRunning(): Boolean = state != null

    fun activePlanId(): Long? = state?.planId

    fun shouldTrack(packageName: String?): Boolean = inspect(packageName)?.shouldTrack ?: false

    fun inspect(packageName: String?): EnginePackageDecision? {
        val current = state ?: return null
        val eligiblePackage = !packageName.isNullOrBlank() && packageName != "com.planlock.app"
        val whitelisted = packageName in current.whitelist
        val exempt = packageName in current.exemptions
        return EnginePackageDecision(
            planId = current.planId,
            snapshotGeneration = current.exemptionSnapshotGeneration,
            classifiedInSnapshot = packageName in current.classifiedPackages,
            exemptInSnapshot = exempt,
            whitelisted = whitelisted,
            shouldTrack = eligiblePackage && !whitelisted && !exempt,
        )
    }
}
