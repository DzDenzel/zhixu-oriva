package com.planlock.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.dto.BackupPasswordSetRequest
import com.planlock.app.record.RecordStore
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
import com.planlock.design.InkText
import com.planlock.design.InkTone

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
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sync: com.planlock.app.sync.AppSync? = null,
) {
    private suspend fun serialize(block: suspend () -> Unit) {
        if (sync == null) block() else sync.withLocalMutation(block)
    }
    suspend fun requirement(plan: PlanSnapshotEntity, action: ProtectedPlanAction): PlanActionRequirement {
        val clock = currentClock()
        if (!requiresPassword(plan, action, clockMinutes(clock), OccurrenceDate.from(clock), isGateEnabled())) return PlanActionRequirement.FREE
        return if (passwordStore.hasPassword()) PlanActionRequirement.PASSWORD else PlanActionRequirement.SETUP
    }

    fun needsEndChoice(plan: PlanSnapshotEntity, action: ProtectedPlanAction): Boolean {
        val clock = currentClock()
        return needsEndChoice(plan, action, clockMinutes(clock), OccurrenceDate.from(clock))
    }

    fun nextRepeatDescription(plan: PlanSnapshotEntity): String = nextRepeatDescription(plan, currentClock())

    suspend fun perform(
        plan: PlanSnapshotEntity,
        action: ProtectedPlanAction,
        password: String?,
        editOperation: (suspend () -> Unit)? = null,
        endChoice: EndPlanChoice? = null,
    ): PlanActionResult {
        if (plan.scope != store.currentScope()) return PlanActionResult.Failed("账号已变化，请重新打开计划")
        val clock = currentClock()
        val protected = requiresPassword(plan, action, clockMinutes(clock), OccurrenceDate.from(clock), isGateEnabled())
        if (needsEndChoice(plan, action) && endChoice == null) return PlanActionResult.Failed("请选择结束范围")
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
                        check(plan.scope == store.currentScope()) { "账号已变化" }
                        val current = checkNotNull(store.plan(plan.id)) { "计划已不存在" }
                        val currentClock = currentClock()
                        check(com.planlock.app.record.PlanOccurrence.canToggleExecution(current, clockMinutes(currentClock), OccurrenceDate.from(currentClock))) { "今天这次已结束" }
                        if (endChoice != null) {
                            // A stale/repeated selection must never become a fresh enable/disable toggle.
                            store.endOccurrence(current.id, nowMillis(), endChoice == EndPlanChoice.ENTIRE_PLAN,
                                if (protected) "backup_password" else "user_ended")
                        } else {
                            store.setPlanEnabled(current.id, plan.status != 1, nowMillis())
                        }
                    }
                }
                ProtectedPlanAction.DELETE -> {
                    serialize {
                        check(plan.scope == store.currentScope()) { "账号已变化" }
                        store.deletePlan(plan.id)
                    }
                }
                ProtectedPlanAction.EDIT -> {
                    check(plan.scope == store.currentScope()) { "账号已变化" }
                    checkNotNull(editOperation) { "缺少编辑保存动作" }.invoke()
                    if (protected) {
                        serialize {
                            check(plan.scope == store.currentScope()) { "账号已变化" }
                            store.plan(plan.id)?.let {
                                store.setPlanEnabled(it.id, false, nowMillis())
                            }
                        }
                    }
                }
            }
            sync?.localChangesCommitted()
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
        endChoice: EndPlanChoice? = null,
    ): PlanActionResult {
        return try {
            passwordStore.set(password)
            val remoteSynced = runCatching {
                api.setBackupPassword(BackupPasswordSetRequest(password)).code == 0
            }.getOrDefault(false)
            when (val result = perform(plan, action, password, editOperation, endChoice)) {
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
    val choosingEnd: Boolean = false,
    val endChoice: EndPlanChoice? = null,
    val failure: String? = null,
)

suspend fun requestOrRunPlanAction(
    gate: PlanActionGate,
    plan: PlanSnapshotEntity,
    action: ProtectedPlanAction,
    editOperation: (suspend () -> Unit)? = null,
    onPrompt: (PendingPlanAction) -> Unit,
    onCompleted: (String?) -> Unit,
) {
    dispatchPlanAction(
        PendingPlanAction(plan, action, false, editOperation, onCompleted),
        choosingEnd = gate.needsEndChoice(plan, action),
        requirement = { gate.requirement(plan, action) },
        perform = { gate.perform(plan, action, null, editOperation) },
        onPrompt = onPrompt,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanActionDialogHost(
    gate: PlanActionGate,
    pending: PendingPlanAction?,
    onDismiss: () -> Unit,
) {
    if (pending == null) return
    val scope = rememberCoroutineScope()
    var stage by remember(pending) { mutableStateOf(pending) }
    val current = stage
    if (current.choosingEnd) {
        var choosing by remember(pending) { mutableStateOf(false) }
        var choiceError by remember(pending) { mutableStateOf<String?>(null) }
        ModalBottomSheet(onDismissRequest = { if (!choosing) onDismiss() }, containerColor = InkTone.raised,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("结束「${current.plan.name}」", style = MaterialTheme.typography.titleLarge, color = InkTone.text,
                    modifier = Modifier.padding(bottom = 16.dp))
                EndPlanChoice.entries.forEach { choice ->
                    Surface(
                        enabled = !choosing,
                        onClick = {
                            choosing = true
                            scope.launch {
                                try {
                                    val selected = current.copy(choosingEnd = false, endChoice = choice)
                                    dispatchPlanAction(selected, false,
                                        requirement = { gate.requirement(selected.plan, selected.action) },
                                        perform = { gate.perform(selected.plan, selected.action, null, selected.editOperation, choice) },
                                        onPrompt = { stage = it },
                                    )
                                    if (stage == current) onDismiss()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { choiceError = "操作未完成，请重试" }
                                finally { choosing = false }
                            }
                        },
                        color = InkTone.raised,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) {
                        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (choice == EndPlanChoice.TODAY) "只结束今天" else "结束整个计划", style = MaterialTheme.typography.titleMedium, color = InkTone.text)
                            Text(if (choice == EndPlanChoice.TODAY) "今天这次记为提前结束，${gate.nextRepeatDescription(current.plan)}照常开始"
                                else "今天这次记为提前结束，之后不再自动开始，可在计划页重新开启", style = InkText.meta, color = InkTone.soft)
                        }
                    }
                    if (choice == EndPlanChoice.TODAY) HorizontalDivider(color = InkTone.hairline)
                }
                choiceError?.let { Text(it, color = InkTone.text, style = InkText.meta) }
                TextButton(enabled = !choosing, onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                    Text("取消", color = InkTone.soft)
                }
            }
        }
        return
    }
    if (current.failure != null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("操作未完成") }, text = { Text(current.failure) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
        return
    }
    var password by remember(pending) { mutableStateOf("") }
    var error by remember(pending) { mutableStateOf<String?>(null) }
    var submitting by remember(pending) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (current.setupRequired) "设置备用密码后继续" else "输入备用密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("执行时段内${current.action.label}计划需要备用密码。")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(if (current.setupRequired) "设置 4–16 位备用密码" else "备用密码") },
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
                        val result = if (current.setupRequired) {
                            gate.setPasswordAndPerform(current.plan, current.action, password, current.editOperation, current.endChoice)
                        } else {
                            gate.perform(current.plan, current.action, password, current.editOperation, current.endChoice)
                        }
                        when (result) {
                            is PlanActionResult.Completed -> {
                                onDismiss()
                                current.onCompleted(result.warning)
                            }
                            PlanActionResult.SetupRequired -> error = "尚未设置备用密码"
                            is PlanActionResult.InvalidPassword -> error = "密码错误，还可尝试 ${result.remainingAttempts} 次"
                            is PlanActionResult.Cooldown -> error = "尝试过多，请 ${((result.remainingMillis + 999) / 1000)} 秒后再试"
                            is PlanActionResult.Failed -> error = result.message
                        }
                        submitting = false
                    }
                },
            ) { Text(if (current.setupRequired) "设置并继续" else "确认") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("取消") } },
    )
}
