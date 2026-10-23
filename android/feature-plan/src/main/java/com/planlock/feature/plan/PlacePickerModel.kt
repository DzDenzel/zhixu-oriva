package com.planlock.feature.plan

import android.content.Context
import com.planlock.app.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 地图选点状态（v4.3 U5 改版：附近地点列表 + 输入联想；codex 原有的拖动 / 搜索 / 当前位置语义不变）。
 * [nearby] 第一条是地图中心本身，其后为周边地点，均按离「我」由近到远；[selectedIndex] 为列表中选中的一条（-1 = 只选了地图点）。
 */
data class PlacePickerState(
    val selected: PlaceSelection,
    val results: List<PlaceSelection> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val nearby: List<PlaceHit> = emptyList(),
    val nearbyLoading: Boolean = false,
    val selectedIndex: Int = -1,
    val suggestions: List<PlaceHit> = emptyList(),
    val myLocation: PlaceSelection? = null,
    val locating: Boolean = false,
)

/** 只选了地图点、还没有名称时的占位名。 */
internal const val MAP_POINT_NAME = "地图上的位置"

class PlacePickerModel(context: Context, private val scope: CoroutineScope, initial: PlaceSelection?) {
    private val app = context.applicationContext
    private val source = placeSource(app)
    private val mutable = MutableStateFlow(PlacePickerState(initial ?: PlaceSelection(MAP_POINT_NAME, 31.2304, 121.4737)))
    val state = mutable.asStateFlow()
    private var nearbyJob: Job? = null
    private var searchJob: Job? = null

    init { refreshNearby(mutable.value.selected.latitude, mutable.value.selected.longitude, keepSelected = initial != null) }

    /** 用户拖动地图：中心即新选点，停 400ms 后反查名称并刷新附近列表。 */
    fun mapMoved(latitude: Double, longitude: Double) {
        val old = mutable.value.selected
        if (abs(old.latitude - latitude) < 0.000001 && abs(old.longitude - longitude) < 0.000001) return
        val keepName = mutable.value.nearby.isEmpty() && old.name.isNotBlank() && old.name != MAP_POINT_NAME
        mutable.value = mutable.value.copy(selected = PlaceSelection(if (keepName) old.name else MAP_POINT_NAME, latitude, longitude), selectedIndex = -1, error = null)
        refreshNearby(latitude, longitude, keepSelected = false, debounceMs = 400)
    }

    /** 选中附近列表中的一条（地图随之居中）。 */
    fun choose(index: Int) {
        val hit = mutable.value.nearby.getOrNull(index) ?: return
        mutable.value = mutable.value.copy(selected = hit.toSelection(), selectedIndex = index, error = null)
    }

    /** 选中一条联想结果：移到那里并刷新它的附近。 */
    fun pick(hit: PlaceHit) {
        searchJob?.cancel()
        mutable.value = mutable.value.copy(selected = hit.toSelection(), suggestions = emptyList(), results = emptyList(), error = null)
        refreshNearby(hit.latitude, hit.longitude, keepSelected = true)
    }

    fun select(place: PlaceSelection) = pick(PlaceHit(place.name, null, place.latitude, place.longitude))
    fun rename(name: String) { mutable.value = mutable.value.copy(selected = mutable.value.selected.copy(name = name)) }

    /** 输入联想（调用方已做 300ms 防抖）。 */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { mutable.value = mutable.value.copy(suggestions = emptyList(), searching = false); return }
        searchJob = scope.launch {
            mutable.value = mutable.value.copy(searching = true, error = null)
            try {
                val current = mutable.value
                val hits = source.suggest(query.trim(), current.myLocation ?: current.selected).take(8)
                mutable.value = mutable.value.copy(suggestions = hits, results = hits.map { it.toSelection() }, searching = false,
                    error = if (hits.isEmpty()) "没有找到「${query.trim()}」，可拖动地图选点" else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(searching = false, error = "搜索暂不可用，可拖动地图选点") }
        }
    }

    /** 回到当前位置（权限由界面先申请；这里不弹权限）。 */
    fun useCurrent() = scope.launch {
        mutable.value = mutable.value.copy(locating = true)
        val place = SystemLocationProvider.get(app).resolve(force = true)
        if (place == null) {
            mutable.value = mutable.value.copy(locating = false, error = "暂未获取当前位置，可搜索或拖动地图")
        } else {
            val me = PlaceSelection(place.displayName, place.latitude, place.longitude)
            mutable.value = mutable.value.copy(locating = false, myLocation = me, selected = me, error = null)
            refreshNearby(me.latitude, me.longitude, keepSelected = false)
        }
    }

    fun mapUnavailable() { mutable.value = mutable.value.copy(error = "部分地图未载入，可稍后重试、搜索或直接选择下方地点") }

    private fun refreshNearby(latitude: Double, longitude: Double, keepSelected: Boolean, debounceMs: Long = 0) {
        nearbyJob?.cancel()
        nearbyJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            mutable.value = mutable.value.copy(nearbyLoading = true)
            val hits = try { source.nearby(latitude, longitude, mutable.value.myLocation) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            val current = mutable.value
            mutable.value = if (keepSelected || hits.isEmpty()) current.copy(nearby = hits, nearbyLoading = false,
                selectedIndex = hits.indexOfFirst { abs(it.latitude - current.selected.latitude) < 0.00001 && abs(it.longitude - current.selected.longitude) < 0.00001 })
            else current.copy(nearby = hits, nearbyLoading = false, selectedIndex = 0, selected = hits.first().toSelection())
        }
    }
}
