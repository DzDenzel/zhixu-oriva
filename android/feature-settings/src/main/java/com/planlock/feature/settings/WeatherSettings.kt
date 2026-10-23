package com.planlock.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planlock.app.api.SystemLocationState
import com.planlock.app.api.WeatherClient
import com.planlock.app.api.WeatherLocation
import com.planlock.app.api.WeatherSnapshot
import com.planlock.design.InkText
import com.planlock.design.InkTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 设置 › 天气（v4.3，负责人 2026-10-19「学习正常的天气应用」）：
 * 像天气应用的城市列表——第一张是「我的位置」（随定位），其后是手动添加的城市；每张卡都显示当地现在的温度和天气，
 * 正在用于今日页的那张打勾。添加城市进入全屏搜索页：空时给热门城市，输入即出结果。
 * 今日页只显示一个地方：手动城市优先（WeatherLocationChoice）。
 * v4.4（负责人 2026-10-19「点回我的位置会自动删除手动的」）：点「我的位置」不再删城市——把它「停放」在 [ParkedCity]
 * （本机 UI 偏好），卡片留在列表里、不打勾，点它即切回；只有「移除」才真正删掉。
 */

/** 被切走、但仍留在列表里的手动城市（只存本机 UI 偏好，不参与同步）。 */
internal object ParkedCity {
    private const val PREFS = "oriva_ui"
    fun get(context: android.content.Context): WeatherLocation? {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val name = p.getString("weather_parked_name", null) ?: return null
        val lat = p.getString("weather_parked_lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("weather_parked_lon", null)?.toDoubleOrNull() ?: return null
        return WeatherLocation(name, lat, lon, p.getString("weather_parked_tz", null) ?: "auto")
    }
    fun put(context: android.content.Context, location: WeatherLocation) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString("weather_parked_name", location.displayName)
            .putString("weather_parked_lat", location.latitude.toString())
            .putString("weather_parked_lon", location.longitude.toString())
            .putString("weather_parked_tz", location.timezone)
            .apply()
    }
    fun clear(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .remove("weather_parked_name").remove("weather_parked_lat").remove("weather_parked_lon").remove("weather_parked_tz")
            .apply()
    }
}

@Composable
internal fun WeatherLocationsSection(
    manual: WeatherLocation?,
    parked: WeatherLocation?,
    located: SystemLocationState,
    onUseMyLocation: () -> Unit,
    onAddCity: () -> Unit,
    onActivateCity: (WeatherLocation) -> Unit,
    onRemoveCity: () -> Unit,
) {
    val city = manual ?: parked
    val client = remember { WeatherClient() }
    val myWeather by produceState<WeatherSnapshot?>(null, located.location) {
        value = located.location?.let { runCatching { client.current(it) }.getOrNull() }
    }
    val manualWeather by produceState<WeatherSnapshot?>(null, city) {
        value = city?.let { runCatching { client.current(it) }.getOrNull() }
    }
    val enableAction: (@Composable () -> Unit)? =
        if (located.location == null && !located.resolving) { { TextButton(onClick = onUseMyLocation) { Text("开启定位") } } } else null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WeatherPlaceCard(
            heading = "我的位置",
            name = when {
                located.location != null -> located.location!!.displayName.substringBefore(" · ")
                located.resolving -> "正在定位…"
                else -> "未开启定位"
            },
            detail = located.location?.displayName?.substringAfter(" · ", "")?.takeIf { it.isNotBlank() }
                ?: if (located.location == null && !located.resolving) "开启后天气随你所在的城市变化" else null,
            snapshot = myWeather,
            active = manual == null && located.location != null,
            leading = { Icon(Icons.Filled.NearMe, null, tint = InkTone.accent, modifier = Modifier.size(16.dp)) },
            onClick = onUseMyLocation,
            action = enableAction,
        )
        if (city != null) WeatherPlaceCard(
            heading = "手动添加",
            name = city.displayName.substringBefore(" · "),
            detail = city.displayName.substringAfter(" · ", "").takeIf { it.isNotBlank() },
            snapshot = manualWeather,
            active = manual != null,
            leading = null,
            onClick = { if (manual == null) onActivateCity(city) },
            action = { TextButton(onClick = onRemoveCity) { Text("移除") } },
        )
        Surface(
            onClick = onAddCity,
            shape = RoundedCornerShape(18.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
            border = BorderStroke(1.dp, InkTone.hairline),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Add, null, tint = InkTone.text)
                Text(if (city == null) "添加城市" else "换一个城市", style = MaterialTheme.typography.titleMedium, color = InkTone.text)
            }
        }
        // 什么都没设置时没有「打勾的那一处」，这句不出现（题签已说明未设置时不显示天气）
        if (city != null || located.location != null)
            Text("今日页显示打勾的那一处，点另一张即可切换。",
                style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun WeatherPlaceCard(
    heading: String,
    name: String,
    detail: String?,
    snapshot: WeatherSnapshot?,
    active: Boolean,
    leading: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    action: (@Composable () -> Unit)?,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = InkTone.raised,
        border = if (active) BorderStroke(1.5.dp, InkTone.accent) else null,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "$heading，$name" + (snapshot?.let { "，${weatherText(it.weatherCode)} ${it.temperatureC.toInt()} 度" } ?: "") +
                if (active) "，今日页正在显示" else ""
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                leading?.invoke()
                Text(heading, style = InkText.caption, color = InkTone.soft, modifier = Modifier.weight(1f))
                if (active) Icon(Icons.Filled.Check, null, tint = InkTone.accent, modifier = Modifier.size(18.dp))
            }
            Row(verticalAlignment = if (snapshot == null && action != null) Alignment.CenterVertically else Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = InkTone.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detail?.let { Text(it, style = InkText.caption, color = InkTone.soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                snapshot?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${it.temperatureC.toInt()}°", fontFamily = InkTone.display, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, color = InkTone.text)
                        Text(weatherText(it.weatherCode), style = InkText.caption, color = InkTone.soft)
                    }
                }
                // v4.4：没有温度时动作（「开启定位」）放在名称同一行右端，卡片不多出空行；有温度时放在下一行
                if (snapshot == null) action?.invoke()
            }
            if (snapshot != null) action?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { it() } }
        }
    }
}

private val HotCities = listOf("北京", "上海", "广州", "深圳", "杭州", "成都", "南京", "武汉", "西安", "重庆", "香港", "新加坡")

/** 添加城市：全屏搜索页（天气应用的做法）。空时热门城市，输入停 350ms 即搜。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WeatherCitySearchPage(
    search: suspend (String) -> List<WeatherLocation>,
    onDismiss: () -> Unit,
    onSelect: (WeatherLocation) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherLocation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var hotBusy by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); searched = false; loading = false; return@LaunchedEffect }
        delay(350)
        loading = true
        results = try { search(query.trim()) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        loading = false
        searched = true
    }
    com.planlock.design.InkFullScreenPage(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = { Text("添加城市", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            OutlinedTextField(query, { query = it }, singleLine = true,
                placeholder = { Text("输入城市名称") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        query.isNotEmpty() -> IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "清空") }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus))
            if (query.isBlank()) {
                Text("热门城市", style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HotCities.forEach { city ->
                        Surface(
                            onClick = {
                                if (hotBusy == null) scope.launch {
                                    hotBusy = city
                                    val hit = runCatching { search(city).firstOrNull() }.getOrNull()
                                    hotBusy = null
                                    if (hit != null) onSelect(hit) else { query = city }
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = InkTone.raised,
                            border = BorderStroke(1.dp, InkTone.hairline),
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Text(if (hotBusy == city) "$city…" else city, style = MaterialTheme.typography.bodyLarge, color = InkTone.text,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (searched && !loading && results.isEmpty()) item {
                        Text("没有找到「${query.trim()}」，换个写法试试", style = InkText.caption, color = InkTone.soft,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp))
                    }
                    items(results) { location ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onSelect(location) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Text(location.displayName.substringBefore(" · "), style = MaterialTheme.typography.titleMedium, color = InkTone.text)
                            location.displayName.substringAfter(" · ", "").takeIf { it.isNotBlank() }?.let {
                                Text(it, style = InkText.caption, color = InkTone.soft)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 20.dp), color = InkTone.hairline.copy(alpha = .6f))
                    }
                }
            }
        }
    }
}

internal fun weatherText(code: Int): String = when (code) {
    0 -> "晴"
    1, 2 -> "少云"
    3 -> "阴"
    45, 48 -> "雾"
    in 51..67 -> "雨"
    in 71..77 -> "雪"
    in 80..82 -> "阵雨"
    in 85..86 -> "阵雪"
    in 95..99 -> "雷雨"
    else -> "—"
}
