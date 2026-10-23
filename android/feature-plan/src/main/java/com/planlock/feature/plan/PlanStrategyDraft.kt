package com.planlock.feature.plan

import com.planlock.app.record.entity.StrategySnapshotEntity

/** Keeps nullable saved strategy separate from new-plan defaults and explicit edits. */
internal data class PlanStrategyDraft(
    val autoDnd: Boolean?,
    val notifySilence: Boolean?,
    private val baselineAutoDnd: Boolean?,
    private val baselineNotifySilence: Boolean?,
    private val isNewPlan: Boolean,
    val whitelistChanged: Boolean = false,
) {
    val shouldWriteStrategy: Boolean get() = isNewPlan || whitelistChanged || autoDnd != baselineAutoDnd || notifySilence != baselineNotifySilence
    fun withAutoDnd(value: Boolean) = copy(autoDnd = value)
    fun withNotifySilence(value: Boolean) = copy(notifySilence = value)
    fun withWhitelistChanged(value: Boolean) = copy(whitelistChanged = value)

    companion object {
        fun fromSnapshot(snapshot: StrategySnapshotEntity?, isNewPlan: Boolean): PlanStrategyDraft {
            val autoDnd = if (isNewPlan) false else snapshot?.autoDnd
            val notifySilence = if (isNewPlan) false else snapshot?.notifySilence
            return PlanStrategyDraft(autoDnd, notifySilence, autoDnd, notifySilence, isNewPlan)
        }
    }
}

fun strategyStateText(value: Boolean?): String = when (value) { true -> "已开启"; false -> "未开启"; null -> "未配置" }
