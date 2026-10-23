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
        val ownerScope: String,
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
        ownerScope: String = com.planlock.app.api.TokenStore.state.value.dataScope,
    ) {
        val next = State(
            planId = planId,
            whitelist = whitelistPackages.toSet(),
            exemptions = exemptPackages.toSet(),
            classifiedPackages = classifiedPackages.toSet(),
            exemptionSnapshotGeneration = exemptionSnapshotGeneration,
            ownerScope = ownerScope,
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

    fun isRunning(): Boolean = activePlanId() != null

    fun activePlanId(): Long? = state?.takeIf { it.ownerScope == com.planlock.app.api.TokenStore.state.value.dataScope }?.planId

    fun shouldTrack(packageName: String?): Boolean = inspect(packageName)?.shouldTrack ?: false

    fun inspect(packageName: String?): EnginePackageDecision? {
        val current = state ?: return null
        if (current.ownerScope != com.planlock.app.api.TokenStore.state.value.dataScope) return null
        val eligiblePackage = !packageName.isNullOrBlank() && !com.planlock.app.api.HostApplication.owns(packageName)
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
