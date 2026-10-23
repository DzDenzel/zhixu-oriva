package com.planlock.feature.plan

import com.planlock.app.api.PlanTypeDefinition
import com.planlock.app.api.PlanTypes
import com.planlock.app.api.TokenStore
import com.planlock.app.record.RecordStore
import com.planlock.app.sync.AppSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlanTypeCatalogState(val dataScope: String = "", val types: List<PlanTypeDefinition> = PlanTypes.presets,
    val usageCounts: Map<String, Int> = emptyMap(), val error: String? = null)

/** Functional catalog API for the subsequent UI task; every edit uses the existing sync owner. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanTypeCatalog(private val store: RecordStore, private val sync: AppSync, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PlanTypeCatalogState())
    val state = mutable.asStateFlow()
    init {
        scope.launch {
            combine(TokenStore.state.map { it.dataScope }.distinctUntilChanged(), store.localRevision) { owner, _ -> owner }
                .collectLatest { owner ->
                    try {
                        val owned = store.forScope(owner)
                        val types = owned.planTypes()
                        val counts = owned.plans().groupingBy { it.type }.eachCount()
                        mutable.value = PlanTypeCatalogState(owner, PlanTypes.presets + types, counts)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutable.value = PlanTypeCatalogState(owner, error = "类型读取失败，请重试") }
                }
        }
    }
    fun create(name: String, color: String) = mutate { store.createPlanType(name,color) }
    fun rename(key: String, name: String, color: String? = null) = mutate { store.renamePlanType(key,name,color) }
    fun delete(key: String, confirmed: Boolean) = mutate { check(confirmed) { "请先确认删除；相关计划将归入其他" }; store.deletePlanType(key) }
    private fun mutate(block: suspend () -> Unit): Job {
        val owner = mutable.value.dataScope
        return scope.launch {
            try {
                sync.withLocalMutation { check(owner == store.currentScope()) { "账号已变化" }; block() }
                sync.localChangesCommitted()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = error.message ?: "类型保存失败") }
        }
    }
}
