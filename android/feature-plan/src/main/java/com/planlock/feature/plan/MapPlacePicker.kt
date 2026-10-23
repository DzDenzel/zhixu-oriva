package com.planlock.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.planlock.app.api.PlaceSelection
import com.planlock.app.security.Capability
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.views.MapView
import java.io.File

/** Map center is the selected WGS84 point. UI layout may change without changing this contract. */
@OptIn(ExperimentalMaterial3Api::class)
@android.annotation.SuppressLint("ClickableViewAccessibility") // The observer returns false; MapView keeps its gesture/click handling.
@Composable
fun MapPlacePicker(initial: PlaceSelection?, onDismiss: () -> Unit, onPick: (PlaceSelection) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { PlacePickerModel(context,scope,initial) }
    val state by model.state.collectAsState()
    val capabilityGate = com.planlock.design.rememberCapabilityPermissionGate()
    val tileSpec = remember { MapTileSettings(context).read() }
    var query by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = "Oriva/2.1.1 (${context.packageName})"
            osmdroidBasePath = File(context.cacheDir,"oriva-map")
            osmdroidTileCache = File(osmdroidBasePath,"tiles")
            expirationOverrideDuration = java.util.concurrent.TimeUnit.DAYS.toMillis(7)
        }
        MapView(context).apply {
            setMultiTouchControls(true)
            // Only a user's drag changes the selected point; layout and search recentering preserve its name.
            setFlingEnabled(false)
            var touching = false
            setOnTouchListener { _, event ->
                touching = event.actionMasked != android.view.MotionEvent.ACTION_UP && event.actionMasked != android.view.MotionEvent.ACTION_CANCEL
                false
            }
            fun source(template: String, name: String) = object : OnlineTileSourceBase(name,0,18,256,".png",arrayOf("https://t0.tianditu.gov.cn/")) {
                override fun getTileURLString(index: Long): String = template
                    .replace("{z}",MapTileIndex.getZoom(index).toString()).replace("{x}",MapTileIndex.getX(index).toString()).replace("{y}",MapTileIndex.getY(index).toString())
            }
            setTileSource(source(tileSpec.urlTemplate, "OrivaBase-${tileSpec.urlTemplate.hashCode()}"))
            tileSpec.annotationTemplate?.let { template ->
                val provider = org.osmdroid.tileprovider.MapTileProviderBasic(context).apply { setTileSource(source(template, "OrivaLabels-${template.hashCode()}")) }
                overlays.add(org.osmdroid.views.overlay.TilesOverlay(provider, context))
            }
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(state.selected.latitude,state.selected.longitude))
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { if(touching) model.mapMoved(mapCenter.latitude,mapCenter.longitude); return false }
                override fun onZoom(event: ZoomEvent?): Boolean = false
            })
            tileProvider.tileRequestCompleteHandlers.add(android.os.Handler(android.os.Looper.getMainLooper()) { message ->
                if (message.what == MapTileProviderBase.MAPTILE_FAIL_ID) model.mapUnavailable()
                false
            })
        }
    }
    DisposableEffect(map,lifecycle) {
        val observer = LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) map.onResume()
            if(event==Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { lifecycle.removeObserver(observer);map.onPause();map.onDetach() }
    }
    // ── v4.3 U5 版式（负责人 2026-10-19）：搜索 → 地图（中心定位标）→ 附近地点（近的在前）→ 固定「确定」栏 ──
    var hasLocation by remember { mutableStateOf(capabilityGate.access.check(Capability.LOCATION)) }
    LaunchedEffect(query) {
        if (query.isBlank()) { model.search(""); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        model.search(query)
    }
    // 打开时：已授权且没有指定初始地点，就以当前位置为中心
    LaunchedEffect(Unit) { if (initial == null && hasLocation) model.useCurrent() }
    val nearbyList = androidx.compose.foundation.lazy.rememberLazyListState()
    com.planlock.design.InkFullScreenPage(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = { Text("选择地点", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            OutlinedTextField(query, { query = it }, singleLine = true,
                placeholder = { Text("搜索地点、地址") },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(androidx.compose.material.icons.Icons.Filled.Close, "清空") } },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            tileSpec.notice?.let { Text(it, style = com.planlock.design.InkText.caption, color = com.planlock.design.InkTone.soft,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            if (!hasLocation) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("未开启定位：附近地点不会按离你远近排序", style = com.planlock.design.InkText.caption,
                    color = com.planlock.design.InkTone.soft, modifier = Modifier.weight(1f))
                TextButton(onClick = { capabilityGate.request(Capability.LOCATION) { granted -> hasLocation = granted; if (granted) model.useCurrent() } }) { Text("开启定位") }
            }
            if (query.isNotBlank()) {
                // 输入时：整块换成联想结果（近的在前），点一条即移到那里
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.searching && state.suggestions.isEmpty()) item { PickerHint("正在搜索…") }
                    state.error?.takeIf { !state.searching && state.suggestions.isEmpty() }?.let { item { PickerHint(it) } }
                    items(state.suggestions.size) { i ->
                        val hit = state.suggestions[i]
                        Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { query = ""; model.pick(hit) }.padding(horizontal = 20.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart) { PlaceHitRow(hit) }
                        HorizontalDivider(Modifier.padding(start = 20.dp), color = com.planlock.design.InkTone.hairline.copy(alpha = .6f))
                    }
                }
            } else {
                Box(Modifier.weight(0.46f).fillMaxWidth().clipToBounds()) {
                    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { view ->
                        if (kotlin.math.abs(view.mapCenter.latitude - state.selected.latitude) > 0.000001 || kotlin.math.abs(view.mapCenter.longitude - state.selected.longitude) > 0.000001)
                            view.controller.animateTo(GeoPoint(state.selected.latitude, state.selected.longitude))
                    })
                    // 中心定位标：针尖对准地图中心
                    Icon(androidx.compose.material.icons.Icons.Filled.Place, contentDescription = null,
                        tint = com.planlock.design.InkTone.accent,
                        modifier = Modifier.align(Alignment.Center).offset(y = (-18).dp).size(40.dp))
                    Box(Modifier.align(Alignment.Center).size(6.dp).background(com.planlock.design.InkTone.accent.copy(alpha = .35f), androidx.compose.foundation.shape.CircleShape))
                    SmallFloatingActionButton(
                        onClick = { if (hasLocation) model.useCurrent() else capabilityGate.request(Capability.LOCATION) { granted -> hasLocation = granted; if (granted) model.useCurrent() } },
                        containerColor = com.planlock.design.InkTone.raised, contentColor = com.planlock.design.InkTone.text,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).semantics { contentDescription = "回到当前位置" },
                    ) {
                        if (state.locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(androidx.compose.material.icons.Icons.Filled.MyLocation, null)
                    }
                    Text(tileSpec.attribution, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .8f)).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
                Text("附近地点", style = com.planlock.design.InkText.caption, color = com.planlock.design.InkTone.soft,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(0.54f).fillMaxWidth(), state = nearbyList) {
                    if (state.nearbyLoading && state.nearby.isEmpty()) item { PickerHint("正在查找附近地点…") }
                    if (!state.nearbyLoading && state.nearby.isEmpty()) {
                        item { PickerHint(state.error ?: "附近没有查到地点名称，可以给这里起个名字") }
                        // 没有地点名可选时（如手机无系统地理编码服务），允许直接给地图点命名，避免地点栏只写「地图上的位置」
                        item {
                            androidx.compose.material3.OutlinedTextField(
                                value = state.selected.name.takeUnless { it == MAP_POINT_NAME }.orEmpty(),
                                onValueChange = { model.rename(it.ifBlank { MAP_POINT_NAME }) },
                                singleLine = true,
                                label = { Text("地点名称") },
                                placeholder = { Text("如：公司、健身房、图书馆") },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(state.nearby.size) { i ->
                        val hit = state.nearby[i]
                        val chosen = i == state.selectedIndex
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { model.choose(i) }.padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { PlaceHitRow(hit, selected = chosen) }
                            if (chosen) Icon(androidx.compose.material.icons.Icons.Filled.Check, "已选", tint = com.planlock.design.InkTone.accent,
                                modifier = Modifier.padding(start = 8.dp).size(20.dp))
                        }
                        HorizontalDivider(Modifier.padding(start = 20.dp), color = com.planlock.design.InkTone.hairline.copy(alpha = .6f))
                    }
                }
            }
            com.planlock.design.InkSaveBar("确定", { onPick(state.selected) }, enabled = state.selected.name.isNotBlank())
        }
    }
}

@Composable
private fun PickerHint(text: String) {
    Text(text, style = com.planlock.design.InkText.caption, color = com.planlock.design.InkTone.soft,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp))
}
