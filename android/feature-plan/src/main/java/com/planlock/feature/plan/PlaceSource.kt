package com.planlock.feature.plan

import android.content.Context
import com.planlock.app.api.PlaceLookup
import com.planlock.app.api.PlaceSelection
import kotlinx.coroutines.CancellationException
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 地点数据源（v4.3，U5 / 负责人 2026-10-19）：界面只依赖这个接口——
 *   suggest：输入地点时的联想（输入提示），按离「我」由近到远；
 *   nearby：地图中心附近的地点列表（第一条是中心点本身的反查名）。
 * 配置Key时使用天地图官方检索/逆地理接口；未配置时保留系统地理编码兜底。坐标约定见 [PlaceHit]。
 */

/** 一个候选地点。坐标为 WGS84（与 PlaceSelection 一致；GCJ-02 的 SDK 结果由实现方换算）。[distanceM] 离当前位置的直线距离，未知为 null。 */
data class PlaceHit(val name: String, val address: String?, val latitude: Double, val longitude: Double, val distanceM: Int? = null) {
    fun toSelection() = PlaceSelection(name, latitude, longitude)
}

interface PlaceSource {
    /** 输入联想；[near] 为当前位置（可空），用于排序与就近优先。 */
    suspend fun suggest(query: String, near: PlaceSelection?): List<PlaceHit>
    /** 地图中心附近；第一条为中心点本身（反查名），其后为周边地点。 */
    suspend fun nearby(centerLat: Double, centerLon: Double, near: PlaceSelection?): List<PlaceHit>
}

/** 现有能力的适配：系统 Geocoder + 天气城市搜索兜底。 */
class SystemPlaceSource(context: Context) : PlaceSource {
    private val lookup = PlaceLookup(context.applicationContext)
    override suspend fun suggest(query: String, near: PlaceSelection?): List<PlaceHit> =
        sortByDistance(lookup.search(query).map { splitPlaceName(it.name).let { (n, a) -> PlaceHit(n, a, it.latitude, it.longitude) } }, near)
    override suspend fun nearby(centerLat: Double, centerLon: Double, near: PlaceSelection?): List<PlaceHit> =
        sortByDistance(listOfNotNull(lookup.reverse(centerLat, centerLon)?.let { splitPlaceName(it.name).let { (n, a) -> PlaceHit(n, a, centerLat, centerLon) } }), near)
}

/** 当前构建使用的数据源。 */
fun placeSource(context: Context): PlaceSource = if (BuildConfig.TIANDITU_KEY.isNotBlank()) TiandituPlaceSource(BuildConfig.TIANDITU_KEY) else SystemPlaceSource(context)

class TiandituPlaceSource(key: String) : PlaceSource {
    private val client = com.planlock.app.api.TiandituPlaceClient(key)
    override suspend fun suggest(query: String, near: PlaceSelection?): List<PlaceHit> =
        distinctPlaces(sortByDistance(client.search(query, near).map { PlaceHit(it.name, it.address, it.latitude, it.longitude) }, near))
    override suspend fun nearby(centerLat: Double, centerLon: Double, near: PlaceSelection?): List<PlaceHit> {
        val center = PlaceSelection(MAP_POINT_NAME, centerLat, centerLon)
        return loadNearbyPlaces(center, near,
            reverse = { client.reverse(centerLat, centerLon)?.let { PlaceHit(it.name, it.address, centerLat, centerLon) } },
            search = { keyword -> client.search(keyword, center, radius = 1_000).map { PlaceHit(it.name, it.address, it.latitude, it.longitude) } })
    }
}

/** The provider requires keywords. Merge real landmark candidates; never claim exhaustive POI coverage. */
internal suspend fun loadNearbyPlaces(
    center: PlaceSelection,
    near: PlaceSelection?,
    reverse: suspend () -> PlaceHit?,
    search: suspend (String) -> List<PlaceHit>,
): List<PlaceHit> {
    var succeeded = false
    var lastFailure: Exception? = null
    suspend fun <T> independently(block: suspend () -> T): T? = try {
        block().also { succeeded = true }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { lastFailure = failure; null }

    val point = independently(reverse)
    val around = listOf("楼", "店", "站").flatMap { keyword -> independently { search(keyword) }.orEmpty() }
        .filter { distanceMeters(center.latitude, center.longitude, it.latitude, it.longitude) in 1..1_000 }
        .distinctBy { Triple(it.name, it.latitude, it.longitude) }
    if (!succeeded) throw lastFailure ?: java.io.IOException("地图检索暂不可用")
    return distinctPlaces(listOfNotNull(point?.let { sortByDistance(listOf(it), near ?: center).single() }) + sortByDistance(around, near ?: center))
}

/** Provider duplicates can differ by full-width characters and a few metres. Keep the nearest real row. */
internal fun distinctPlaces(hits: List<PlaceHit>): List<PlaceHit> {
    val groups = mutableMapOf<String, MutableList<PlaceHit>>()
    return hits.filter { hit ->
        val name = java.text.Normalizer.normalize(hit.name, java.text.Normalizer.Form.NFKC).filterNot(Char::isWhitespace)
        val sameName = groups.getOrPut(name) { mutableListOf() }
        if (sameName.any { distanceMeters(it.latitude, it.longitude, hit.latitude, hit.longitude) <= 20 }) false
        else { sameName += hit; true }
    }
}

private val adminPrefix = Regex("^(.{2,}?(?:省|自治区|特别行政区))?(.{2,}?(?:市|自治州|地区|盟))?(.{1,}?(?:区|县|旗))?(.{1,}?(?:镇|乡|街道))?")

/**
 * 系统地理编码常把整串地址当名字（「广东省佛山市南海区大沥镇南面…」），列表里一行放不下也看不出是哪儿。
 * 拆成「名称 + 地址」：去掉开头的省 / 市 / 区县 / 乡镇作为第二行，剩下的作标题；剩下不足 2 个字时保留原样。
 */
internal fun splitPlaceName(full: String): Pair<String, String?> {
    val m = adminPrefix.find(full) ?: return full to null
    val prefix = m.value
    val rest = full.substring(prefix.length).trim(' ', '，', ',')
    return if (prefix.isEmpty() || rest.length < 2) full to null else rest to prefix
}

/** 补上离 [near] 的距离，并由近到远排序；没有当前位置时保持原顺序。 */
internal fun sortByDistance(hits: List<PlaceHit>, near: PlaceSelection?): List<PlaceHit> {
    if (near == null) return hits
    return hits.map { it.copy(distanceM = it.distanceM ?: distanceMeters(near.latitude, near.longitude, it.latitude, it.longitude)) }
        .sortedBy { it.distanceM ?: Int.MAX_VALUE }
}

/** 两点直线距离（米，球面近似）。 */
internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return (2 * r * asin(sqrt(a))).roundToInt()
}

/** 「80 米」「1.2 公里」「12 公里」。 */
internal fun distanceLabel(meters: Int): String = when {
    meters < 1_000 -> "${(meters / 10).coerceAtLeast(1) * 10} 米".let { if (meters < 10) "10 米内" else it }
    meters < 10_000 -> "%.1f 公里".format(java.util.Locale.ROOT, meters / 1000.0)
    else -> "${(meters / 1000.0).roundToInt()} 公里"
}
