package com.planlock.app.record

import com.planlock.app.api.ConnectionStore
import com.planlock.app.api.TokenStore
import com.planlock.app.api.AuthState
import com.planlock.app.record.entity.PendingRecordEntity
import com.planlock.app.record.entity.ExecutionHistoryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun historyScopeKey(instance: String, owner: Long?) = "$instance\n${owner ?: "local"}"
internal fun currentHistoryScope(): String = ownedHistoryScope(TokenStore.state.value,ConnectionStore.state.value.instanceId)
internal fun ownedHistoryScope(auth: AuthState, instance: String?) = auth.copy(instanceId = instance).dataScope
fun visibleHistoryScope(): String = visibleHistoryScope(TokenStore.state.value,ConnectionStore.state.value.instanceId)
internal fun visibleHistoryScope(auth: AuthState, instance: String?): String {
    return auth.copy(instanceId = instance).dataScope
}
internal fun historyKey(type: String, clientId: String?, id: Long): String = if (!clientId.isNullOrBlank()) "$type:client:$clientId" else "$type:server:$id"
internal fun PendingRecordEntity.toHistory(scope: String) = ExecutionHistoryEntity(scope,historyKey(type,clientId,id),type,planId=requireNotNull(planId),planDate=planDate,status=status,startedAt=startedAt,endedAt=endedAt,unlockAt=unlockAt,method=method,clientId=clientId,localOrigin=true)

@Serializable
internal data class PlanStatusChange(val date: String, val status: Int)

internal fun appendStatus(raw: String?, nowMillis: Long, status: Int): String {
    val old = raw?.let { Json.decodeFromString<List<PlanStatusChange>>(it) }.orEmpty()
    val date = SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(Date(nowMillis))
    return Json.encodeToString((old + PlanStatusChange(date,status)).takeLast(50))
}
