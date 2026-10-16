package com.planlock.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.dto.BackupPasswordSetRequest
import com.planlock.app.record.RecordEventFactory
import com.planlock.app.record.RecordStore
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.security.BackupPasswordResult
import com.planlock.app.security.BackupPasswordStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.Calendar
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

enum class ProtectedPlanAction(val label: String) {
    PAUSE("结束"),
    DELETE("删除"),
    EDIT("编辑"),
}

enum class PlanActionRequirement { FREE, PASSWORD, SETUP }

sealed interface PlanActionResult {
    data class Completed(val warning: String? = null) : PlanActionResult
    data object SetupRequired : PlanActionResult
    data class InvalidPassword(val remainingAttempts: Int) : PlanActionResult
    data class Cooldown(val remainingMillis: Long) : PlanActionResult
    data class Failed(val message: String) : PlanActionResult
}

class PlanActionGate(
    private val store: RecordStore,
    private val api: PlanLockApi,
    private val passwordStore: BackupPasswordStore,
    private val preferences: DataStore<Preferences>? = null,
    private val recordFactory: RecordEventFactory = RecordEventFactory(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sync: com.planlock.app.sync.AppSync? = null,
) {
    private suspend fun serialize(block: suspend () -> Unit) {
        if (sync == null) block() else sync.withSessionChange(block)
    }
    suspend fun requirement(plan: PlanSnapshotEntity, action: ProtectedPlanAction): PlanActionRequirement {
        val clock = currentClock()
        if (!requiresPassword(plan, action, clockMinutes(clock), OccurrenceDate.from(clock), isGateEnabled())) return PlanActionRequirement.FREE
        return if (passwordStore.hasPassword()) PlanActionRequirement.PASSWORD else PlanActionRequirement.SETUP
    }

    suspend fun perform(
        plan: PlanSnapshotEntity,
        action: ProtectedPlanAction,
        password: String?,
        editOperation: (suspend () -> Unit)? = null,
    ): PlanActionResult {
        val clock = currentClock()
        val protected = requiresPassword(plan, action, clockMinutes(clock), OccurrenceDate.from(clock), isGateEnabled())
        val endingEarly = action == ProtectedPlanAction.PAUSE && plan.status == 1 &&
            PlanOccurrence.isWithinOccurrence(plan, clockMinutes(clock), OccurrenceDate.from(clock))
        if (protected) {
            when (val verification = password?.let { passwordStore.verify(it) } ?: BackupPasswordResult.NotSet) {
                BackupPasswordResult.Verified -> Unit
                BackupPasswordResult.NotSet -> return PlanActionResult.SetupRequired
                is BackupPasswordResult.Invalid -> return PlanActionResult.InvalidPassword(verification.remainingAttempts)
                is BackupPasswordResult.Cooldown -> return PlanActionResult.Cooldown(verification.remainingMillis)
            }
        }
        return try {
            when (action) {
                ProtectedPlanAction.PAUSE -> {
                    serialize {
                        val current = checkNotNull(store.plan(plan.id)) { "计划已不存在" }
                        store.updateLocalPlan(current.copy(status = if (plan.status == 1) 0 else 1))
                    }
                    if (endingEarly) {
                        store.enqueueExecution(recordFactory.executionEnded(plan, nowMillis(), completed = false))
                        store.enqueueUnlock(recordFactory.unlock(plan.id, nowMillis(), if (protected) "backup_password" else "user_ended"))
                    }
                }
                ProtectedPlanAction.DELETE -> {
                    serialize {
                        store.deletePlan(plan.id)
                    }
                }
                ProtectedPlanAction.EDIT -> {
                    checkNotNull(editOperation) { "缺少编辑保存动作" }.invoke()
                    if (protected) {
                        serialize {
                            store.plan(plan.id)?.let {
                                store.updateLocalPlan(it.copy(status = 0))
                            }
                        }
                    }
                }
            }
            try { sync?.pushPendingRecords() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            PlanActionResult.Completed()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PlanActionResult.Failed("操作未完成，请重试")
        }
    }

    suspend fun setPasswordAndPerform(
        plan: PlanSnapshotEntity,
        action: ProtectedPlanAction,
        password: String,
        editOperation: (suspend () -> Unit)? = null,
    ): PlanActionResult {
        return try {
            passwordStore.set(password)
            val remoteSynced = runCatching {
                api.setBackupPassword(BackupPasswordSetRequest(password)).code == 0
            }.getOrDefault(false)
            when (val result = perform(plan, action, password, editOperation)) {
                is PlanActionResult.Completed -> result.copy(
                    warning = if (remoteSynced) null else "已在本机设置；服务端暂未同步，请联网后在设置页重新设置",
                )
                else -> result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PlanActionResult.Failed("备用密码保存失败，请重试")
        }
    }

    companion object {
        internal fun requiresPassword(plan: PlanSnapshotEntity, action: ProtectedPlanAction, nowMinutes: Int, date: OccurrenceDate, gateEnabled: Boolean): Boolean {
            if (!gateEnabled) return false
            val inWindow = PlanTime.isActive(plan, nowMinutes, date)
            return when (action) {
                ProtectedPlanAction.PAUSE -> inWindow && plan.status == 1
                ProtectedPlanAction.DELETE, ProtectedPlanAction.EDIT -> inWindow
            }
        }

        private fun clockMinutes(clock: Calendar): Int = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    }

    private suspend fun isGateEnabled(): Boolean = preferences?.data?.first()?.get(PlanLockPreferenceKeys.actionGateEnabled) ?: false

    private fun currentClock(): Calendar = Calendar.getInstance().apply { timeInMillis = nowMillis() }
}

data class PendingPlanAction(
    val plan: PlanSnapshotEntity,
    val action: ProtectedPlanAction,
    val setupRequired: Boolean,
    val editOperation: (suspend () -> Unit)? = null,
    val onCompleted: (String?) -> Unit,
)

suspend fun requestOrRunPlanAction(
    gate: PlanActionGate,
    plan: PlanSnapshotEntity,
    action: ProtectedPlanAction,
    editOperation: (suspend () -> Unit)? = null,
    onPrompt: (PendingPlanAction) -> Unit,
    onCompleted: (String?) -> Unit,
) {
    when (gate.requirement(plan, action)) {
        PlanActionRequirement.FREE -> {
            val result = gate.perform(plan, action, null, editOperation)
            if (result is PlanActionResult.Completed) onCompleted(result.warning)
        }
        PlanActionRequirement.PASSWORD -> onPrompt(PendingPlanAction(plan, action, false, editOperation, onCompleted))
        PlanActionRequirement.SETUP -> onPrompt(PendingPlanAction(plan, action, true, editOperation, onCompleted))
    }
}

@Composable
fun PlanActionDialogHost(
    gate: PlanActionGate,
    pending: PendingPlanAction?,
    onDismiss: () -> Unit,
) {
    if (pending == null) return
    val scope = rememberCoroutineScope()
    var password by remember(pending) { mutableStateOf("") }
    var error by remember(pending) { mutableStateOf<String?>(null) }
    var submitting by remember(pending) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (pending.setupRequired) "设置备用密码后继续" else "输入备用密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("执行时段内${pending.action.label}计划需要备用密码。")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(if (pending.setupRequired) "设置 4–16 位备用密码" else "备用密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting && password.length in 4..16,
                onClick = {
                    submitting = true
                    scope.launch {
                        val result = if (pending.setupRequired) {
                            gate.setPasswordAndPerform(pending.plan, pending.action, password, pending.editOperation)
                        } else {
                            gate.perform(pending.plan, pending.action, password, pending.editOperation)
                        }
                        when (result) {
                            is PlanActionResult.Completed -> {
                                onDismiss()
                                pending.onCompleted(result.warning)
                            }
                            PlanActionResult.SetupRequired -> error = "尚未设置备用密码"
                            is PlanActionResult.InvalidPassword -> error = "密码错误，还可尝试 ${result.remainingAttempts} 次"
                            is PlanActionResult.Cooldown -> error = "尝试过多，请 ${((result.remainingMillis + 999) / 1000)} 秒后再试"
                            is PlanActionResult.Failed -> error = result.message
                        }
                        submitting = false
                    }
                },
            ) { Text(if (pending.setupRequired) "设置并继续" else "确认") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("取消") } },
    )
}
