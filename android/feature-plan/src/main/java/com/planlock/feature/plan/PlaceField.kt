package com.planlock.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.planlock.app.api.PlaceSelection
import com.planlock.app.api.SystemLocationProvider
import com.planlock.app.security.Capability
import com.planlock.design.InkText
import com.planlock.design.InkTone
import kotlinx.coroutines.delay

/**
 * 地点输入（v4.3，U5，负责人 2026-10-19）：
 * - 右侧定位标：先申请定位权限，再打开地图选点；选中的地点直接填回这里（名称 + 坐标）。
 * - 直接输入：停顿 300ms 后联想地图上的地点，离我近的在前，每条带距离；点一条即填入并带上坐标。
 * - 手动改字会解除已带的坐标（地名与坐标不再对应）；定位标着色表示「已在地图上定位」。
 */
@Composable
internal fun PlaceField(
    text: String,
    place: PlaceSelection?,
    onText: (String) -> Unit,
    onPlace: (PlaceSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val source = remember { placeSource(context) }
    val gate = com.planlock.design.rememberCapabilityPermissionGate()
    val myLocation by SystemLocationProvider.get(context).state.collectAsState()
    var focused by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<PlaceHit>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    val near = myLocation.location?.let { PlaceSelection(it.displayName, it.latitude, it.longitude) }

    // 已授权时安静地取一次当前位置，用来给联想排序；不在这里弹权限。
    LaunchedEffect(focused) { if (focused) runCatching { SystemLocationProvider.get(context).resolve() } }
    LaunchedEffect(text, focused, edited) {
        if (!focused || !edited || text.isBlank() || place?.name == text) { hits = emptyList(); return@LaunchedEffect }
        delay(300)
        hits = runCatching { source.suggest(text.trim(), near) }.getOrDefault(emptyList()).take(6)
    }

    if (showPicker) MapPlacePicker(place, onDismiss = { showPicker = false }, onPick = {
        showPicker = false; hits = emptyList(); edited = false; onPlace(it)
    })

    // v4.6（真机，负责人 10-20）：联想不再用浮层下拉——浮层会盖住键盘；改为输入框下方的就地列表，
    // 出结果时把「输入框 + 列表」整块滚到键盘上方可见处
    val reveal = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(hits) { if (hits.isNotEmpty()) { delay(120); reveal.bringIntoView() } }
    Column(modifier.fillMaxWidth().bringIntoViewRequester(reveal)) {
        OutlinedTextField(
            value = text,
            onValueChange = { edited = true; onText(it) },
            label = { Text("地点") },
            placeholder = { Text("输入或在地图上选") },
            singleLine = true,
            supportingText = place?.let { { Text("已在地图上定位", style = InkText.caption, color = InkTone.soft) } },
            trailingIcon = {
                IconButton(onClick = {
                    // 先授权定位再选点；拒绝时仍可打开地图手动拖动或搜索
                    gate.request(Capability.LOCATION) { showPicker = true }
                }) {
                    Icon(Icons.Filled.Place, contentDescription = "在地图上选点",
                        tint = if (place != null) InkTone.accent else InkTone.soft)
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        androidx.compose.animation.AnimatedVisibility(visible = focused && hits.isNotEmpty()) {
            val shown = remember { mutableStateOf(hits) }
            if (hits.isNotEmpty()) shown.value = hits
            androidx.compose.material3.Surface(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = InkTone.raised,
                border = androidx.compose.foundation.BorderStroke(1.dp, InkTone.hairline),
            ) {
                Column {
                    shown.value.take(5).forEachIndexed { index, hit ->
                        if (index > 0) androidx.compose.material3.HorizontalDivider(color = InkTone.hairline, modifier = Modifier.padding(horizontal = 12.dp))
                        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(onClickLabel = "选这个地点") { hits = emptyList(); edited = false; onPlace(hit.toSelection()) }
                            .padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                            PlaceHitRow(hit)
                        }
                    }
                }
            }
        }
    }
}

/** 一条候选：名称（粗）+ 地址（软墨），右侧距离。 */
@Composable
internal fun PlaceHitRow(hit: PlaceHit, selected: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(hit.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) InkTone.accent else InkTone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hit.address?.takeIf { it.isNotBlank() && it != hit.name }?.let {
                Text(it, style = InkText.caption, color = InkTone.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        hit.distanceM?.let { Text(distanceLabel(it), style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(start = 4.dp)) }
    }
}
