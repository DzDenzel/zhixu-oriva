package com.planlock.feature.plan

import android.content.Context
import java.net.URI

data class MapTileSource(val urlTemplate: String, val attribution: String, val annotationTemplate: String? = null, val notice: String? = null)

/** Configured providers must use WGS84/Web Mercator XYZ tiles and supply visible attribution. */
class MapTileSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("map_tile_source", Context.MODE_PRIVATE)
    fun read(): MapTileSource {
        val custom = preferences.getString("url", null)
        if (custom != null) return MapTileSource(custom, preferences.getString("attribution", null) ?: DEFAULT.attribution)
        return if (BuildConfig.TIANDITU_KEY.isBlank()) DEFAULT.copy(notice = "未配置国内地图，可搜索或直接拖动选点")
        else MapTileSource(domesticTiles("vec", BuildConfig.TIANDITU_KEY), "© 天地图", domesticTiles("cva", BuildConfig.TIANDITU_KEY))
    }
    fun save(value: MapTileSource) {
        val url = URI(value.urlTemplate.replace("{z}","1").replace("{x}","1").replace("{y}","1"))
        require(url.scheme == "https" && url.host != null && url.userInfo == null && listOf("{z}","{x}","{y}").all(value.urlTemplate::contains)) { "需要 HTTPS XYZ 瓦片地址" }
        require(value.attribution.isNotBlank()) { "需要地图署名" }
        preferences.edit().putString("url",value.urlTemplate).putString("attribution",value.attribution).apply()
    }
    companion object {
        val DEFAULT = MapTileSource("https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors")
        fun domesticTiles(layer: String, key: String): String {
            require(layer in setOf("vec", "cva"))
            return "https://t0.tianditu.gov.cn/${layer}_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=$layer&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=$key"
        }
    }
}
